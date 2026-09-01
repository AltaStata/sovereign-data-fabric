/*
 * Copyright (c) 2026 AltaStata Inc. All rights reserved.
 *
 * This software is dual-licensed. It is licensed under the Business Source License 1.1 
 * (BSL) for open use and evaluation, with an eventual transition to the Apache 2.0 
 * license on the Change Date.
 * 
 * PATENT NOTICE: Protected by US Patent No. 10,693,660.
 *
 * For the full license text, see the LICENSE.md file in the root of the repository,
 * or https://github.com/AltaStata/sovereign-data-fabric/blob/main/LICENSE.md
 */

package com.altastata.s3gateway.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.altastata.s3gateway.service.ObjectTaggingResult;

class ObjectTaggingXmlTest {

    @Test
    void toXml_roundTripsOwnerAndReaders() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put(ObjectTaggingXml.TAG_OWNER, "bob123");
        tags.put(ObjectTaggingXml.TAG_READERS, "bob123 alice222");

        String xml = ObjectTaggingXml.toXml(tags);
        assertTrue(xml.contains("<Key>owner</Key>"));
        assertTrue(xml.contains("<Value>bob123</Value>"));
        assertTrue(xml.contains("<Value>bob123 alice222</Value>"));
    }

    @Test
    void parsePutTagging_acceptsReadersToAdd() {
        String xml = """
                <Tagging xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <TagSet>
                    <Tag>
                      <Key>readers_to_add</Key>
                      <Value>alice222 catrina777</Value>
                    </Tag>
                  </TagSet>
                </Tagging>
                """;

        ObjectTaggingXml.ParsedPutTagging parsed = ObjectTaggingXml.parsePutTagging(xml);
        assertTrue(parsed.isOk());
        assertEquals(ObjectTaggingXml.TAG_READERS_TO_ADD, parsed.getActionKey());
        assertEquals(2, parsed.getPrincipals().length);
        assertEquals("alice222", parsed.getPrincipals()[0]);
        assertEquals("catrina777", parsed.getPrincipals()[1]);
    }

    @Test
    void parsePutTagging_rejectsReadOnlyReadersTag() {
        String xml = """
                <Tagging xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <TagSet>
                    <Tag><Key>readers</Key><Value>alice222</Value></Tag>
                  </TagSet>
                </Tagging>
                """;

        ObjectTaggingXml.ParsedPutTagging parsed = ObjectTaggingXml.parsePutTagging(xml);
        assertFalse(parsed.isOk());
        assertEquals(ObjectTaggingResult.Status.INVALID_TAG, parsed.getError().getStatus());
    }

    @Test
    void parsePutTagging_rejectsBothActionTags() {
        String xml = """
                <Tagging xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <TagSet>
                    <Tag><Key>readers_to_add</Key><Value>a</Value></Tag>
                    <Tag><Key>readers_to_revoke</Key><Value>b</Value></Tag>
                  </TagSet>
                </Tagging>
                """;

        ObjectTaggingXml.ParsedPutTagging parsed = ObjectTaggingXml.parsePutTagging(xml);
        assertFalse(parsed.isOk());
        assertEquals(ObjectTaggingResult.Status.INVALID_TAG, parsed.getError().getStatus());
    }

    @Test
    void readersToWireFormat_convertsNewlinesToSpaces() {
        assertEquals("bob123 alice222", ObjectTaggingXml.readersToWireFormat("bob123\nalice222\n"));
    }
}
