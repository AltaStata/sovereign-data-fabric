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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.altastata.s3gateway.service.ObjectTaggingResult;

/**
 * Parse and build S3 Object Tagging XML for virtual share/unshare tags.
 */
public final class ObjectTaggingXml {

    public static final String NS = "http://s3.amazonaws.com/doc/2006-03-01/";

    public static final String TAG_OWNER = "owner";
    public static final String TAG_READERS = "readers";
    public static final String TAG_READERS_TO_ADD = "readers_to_add";
    public static final String TAG_READERS_TO_REVOKE = "readers_to_revoke";

    private static final int MAX_TAG_VALUE_LENGTH = 256;
    private static final Pattern AWS_TAG_VALUE_PATTERN =
            Pattern.compile("[\\p{L}\\p{Z}\\p{N}_.:/=+\\-@]*");

    /**
     * Private constructor to prevent instantiation of utility class.
     */
    private ObjectTaggingXml() {
    }

    /**
     * Serializes a tags map to standard S3 Tagging XML format.
     *
     * @param tags target tags map
     * @return serialized S3 format XML string
     */
    public static String toXml(Map<String, String> tags) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<Tagging xmlns=\"").append(NS).append("\">\n");
        sb.append("  <TagSet>\n");
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            sb.append("    <Tag>\n");
            sb.append("      <Key>").append(escapeXml(entry.getKey())).append("</Key>\n");
            sb.append("      <Value>").append(escapeXml(entry.getValue())).append("</Value>\n");
            sb.append("    </Tag>\n");
        }
        sb.append("  </TagSet>\n");
        sb.append("</Tagging>");
        return sb.toString();
    }

    /**
     * Parse PUT tagging body. Returns INVALID_TAG or MALFORMED_XML on failure.
     * On success, map contains exactly one action key with a space-separated value.
     */
    public static ParsedPutTagging parsePutTagging(String xml) {
        if (xml == null || xml.isBlank()) {
            return ParsedPutTagging.error(ObjectTaggingResult.malformedXml());
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            NodeList tagNodes = doc.getElementsByTagNameNS(NS, "Tag");
            if (tagNodes.getLength() == 0) {
                tagNodes = doc.getElementsByTagName("Tag");
            }

            Map<String, String> rawTags = new LinkedHashMap<>();
            for (int i = 0; i < tagNodes.getLength(); i++) {
                Node tagNode = tagNodes.item(i);
                if (!(tagNode instanceof Element tagElement)) {
                    continue;
                }
                String key = textContent(tagElement, "Key");
                String value = textContent(tagElement, "Value");
                if (key != null) {
                    rawTags.put(key, value != null ? value : "");
                }
            }

            if (rawTags.isEmpty()) {
                return ParsedPutTagging.error(ObjectTaggingResult.malformedXml());
            }

            if (rawTags.containsKey(TAG_OWNER) || rawTags.containsKey(TAG_READERS)) {
                return ParsedPutTagging.error(ObjectTaggingResult.invalidTag(
                        "Tag key is read-only: " + (rawTags.containsKey(TAG_OWNER) ? TAG_OWNER : TAG_READERS)));
            }

            boolean hasAdd = rawTags.containsKey(TAG_READERS_TO_ADD);
            boolean hasRevoke = rawTags.containsKey(TAG_READERS_TO_REVOKE);
            if (hasAdd == hasRevoke) {
                return ParsedPutTagging.error(ObjectTaggingResult.invalidTag(
                        "Exactly one of readers_to_add or readers_to_revoke is required"));
            }

            for (Map.Entry<String, String> entry : rawTags.entrySet()) {
                String tagKey = entry.getKey();
                if (!TAG_READERS_TO_ADD.equals(tagKey) && !TAG_READERS_TO_REVOKE.equals(tagKey)) {
                    return ParsedPutTagging.error(ObjectTaggingResult.invalidTag("Unknown tag key: " + tagKey));
                }
                String value = entry.getValue();
                if (value.length() > MAX_TAG_VALUE_LENGTH) {
                    return ParsedPutTagging.error(ObjectTaggingResult.invalidTag("Tag value exceeds 256 characters"));
                }
                if (!AWS_TAG_VALUE_PATTERN.matcher(value).matches()) {
                    return ParsedPutTagging.error(ObjectTaggingResult.invalidTag("Invalid tag value characters"));
                }
            }

            String actionKey = hasAdd ? TAG_READERS_TO_ADD : TAG_READERS_TO_REVOKE;
            String[] principals = splitPrincipals(rawTags.get(actionKey));
            if (principals.length == 0) {
                return ParsedPutTagging.error(ObjectTaggingResult.invalidTag("No principals specified"));
            }

            return ParsedPutTagging.ok(actionKey, principals);
        } catch (Exception e) {
            return ParsedPutTagging.error(ObjectTaggingResult.malformedXml());
        }
    }

    /**
     * Splits space-delimited principal strings into a list of unique values.
     *
     * @param value space-delimited string of principals
     * @return unique array of principal strings
     */
    public static String[] splitPrincipals(String value) {
        if (value == null || value.isBlank()) {
            return new String[0];
        }
        String[] tokens = value.trim().split("\\s+");
        List<String> unique = new ArrayList<>();
        for (String token : tokens) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty() && !unique.contains(trimmed)) {
                unique.add(trimmed);
            }
        }
        return unique.toArray(new String[0]);
    }

    /**
     * Converts a newline-separated list of readers to a space-separated wire format.
     *
     * @param readersAttribute raw newline-separated readers string from storage metadata attributes
     * @return space-separated readers string
     */
    public static String readersToWireFormat(String readersAttribute) {
        if (readersAttribute == null || readersAttribute.isEmpty()) {
            return "";
        }
        String[] parts = readersAttribute.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(trimmed);
            }
        }
        return sb.toString();
    }

    /**
     * Resolves the text content of a nested XML element.
     *
     * @param parent parent element
     * @param localName target child element local name
     * @return child element text content, or null if not found
     */
    private static String textContent(Element parent, String localName) {
        NodeList children = parent.getElementsByTagNameNS(NS, localName);
        if (children.getLength() == 0) {
            children = parent.getElementsByTagName(localName);
        }
        if (children.getLength() == 0) {
            return null;
        }
        return children.item(0).getTextContent();
    }

    /**
     * Escapes standard characters for XML serialization correctness.
     *
     * @param value raw value to escape
     * @return XML-safe escaped string
     */
    private static String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    /**
     * Parsed tagging result structure containing action and parsed principals.
     */
    public static final class ParsedPutTagging {
        private final ObjectTaggingResult error;
        private final String actionKey;
        private final String[] principals;

        /**
         * Private constructor.
         *
         * @param error      the error result, if any
         * @param actionKey  the parsed action key
         * @param principals the parsed array of principals
         */
        private ParsedPutTagging(ObjectTaggingResult error, String actionKey, String[] principals) {
            this.error = error;
            this.actionKey = actionKey;
            this.principals = principals;
        }

        /**
         * Creates a successful parse result.
         *
         * @param actionKey  the action key
         * @param principals the principals
         * @return successful parsed result
         */
        public static ParsedPutTagging ok(String actionKey, String[] principals) {
            return new ParsedPutTagging(null, actionKey, principals);
        }

        /**
         * Creates an error parse result.
         *
         * @param error the object tagging error
         * @return error parsed result
         */
        public static ParsedPutTagging error(ObjectTaggingResult error) {
            return new ParsedPutTagging(error, null, null);
        }

        /**
         * Checks if the parse result is successful.
         *
         * @return true if successful
         */
        public boolean isOk() {
            return error == null;
        }

        /**
         * Gets the associated error result, if any.
         *
         * @return the error result
         */
        public ObjectTaggingResult getError() {
            return error;
        }

        /**
         * Gets the parsed action key.
         *
         * @return the action key
         */
        public String getActionKey() {
            return actionKey;
        }

        /**
         * Gets the parsed array of principals.
         *
         * @return the principals
         */
        public String[] getPrincipals() {
            return principals;
        }
    }
}
