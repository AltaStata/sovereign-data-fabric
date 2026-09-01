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

/*
 * Abstract base class for AWS chunked input streams
 * Based on S3Mock implementation
 */
package com.altastata.s3gateway.util;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public abstract class AbstractAwsInputStream extends InputStream {
    protected static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.UTF_8);
    protected static final byte[] DELIMITER = ";".getBytes(StandardCharsets.UTF_8);
    protected static final byte[] CHECKSUM_HEADER = "x-amz-checksum-".getBytes(StandardCharsets.UTF_8);
    
    protected long readDecodedLength = 0L;
    protected final InputStream source;
    protected long chunkLength = 0L;
    protected String checksum = null;
    protected int chunks = 0;
    
    /**
     * That's the max chunk buffer size used in the AWS implementation.
     */
    private static final int MAX_CHUNK_SIZE = 256 * 1024;
    private final ByteBuffer byteBuffer = ByteBuffer.allocate(MAX_CHUNK_SIZE);
    protected final long decodedLength;

    /**
     * Constructs AbstractAwsInputStream with specified source and length.
     *
     * @param source source input stream
     * @param decodedLength decoded stream length in bytes
     */
    protected AbstractAwsInputStream(InputStream source, long decodedLength) {
        this.source = new BufferedInputStream(source);
        this.decodedLength = decodedLength;
    }

    /**
     * Closes the underlying source input stream.
     *
     * @throws IOException if any I/O errors occur
     */
    @Override
    public void close() throws IOException {
        source.close();
    }

    /**
     * Reads this stream until the byte sequence was found.
     *
     * @param endSequence The byte sequence to look for in the stream. The source stream is read
     *     until the last bytes read are equal to this sequence.
     *
     * @return The bytes read <em>before</em> the end sequence started.
     */
    protected byte[] readUntil(final byte[] endSequence) throws IOException {
        byteBuffer.clear();
        while (!endsWith(byteBuffer.asReadOnlyBuffer(), endSequence)) {
            var c = source.read();
            if (c < 0) {
                return new byte[0];
            }

            var unsigned = (byte) (c & 0xFF);
            byteBuffer.put(unsigned);
        }

        var result = new byte[byteBuffer.position() - endSequence.length];
        byteBuffer.rewind();
        byteBuffer.get(result);
        return result;
    }

    /**
     * Checks if the active ByteBuffer ends with the specified sequence.
     *
     * @param buffer byte buffer to check
     * @param endSequence target byte sequence pattern
     * @return true if matches
     */
    protected boolean endsWith(final ByteBuffer buffer, final byte[] endSequence) {
        var pos = buffer.position();
        if (pos >= endSequence.length) {
            for (var i = 0; i < endSequence.length; i++) {
                if (buffer.get(pos - endSequence.length + i) != endSequence[i]) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Parses chunk length from hex representation.
     *
     * @param hexLengthBytes hex representation bytes
     */
    protected void setChunkLength(byte[] hexLengthBytes) {
        chunkLength = Long.parseLong(new String(hexLengthBytes, StandardCharsets.UTF_8).trim(), 16);
    }

    /**
     * Extracts checksum algorithm and values from headers.
     *
     * @throws IOException if any I/O errors occur
     */
    protected void extractAlgorithmAndChecksum() throws IOException {
        if (checksum == null) {
            readUntil(CHECKSUM_HEADER);
            var typeAndChecksum = readUntil(CRLF);
            var typeAndChecksumString = new String(typeAndChecksum);
            if (!typeAndChecksumString.isBlank()) {
                var split = typeAndChecksumString.split(":");
                if (split.length >= 2) {
                    checksum = split[1];
                }
            }
        }
    }

    /**
     * Resolves the extracted checksum string.
     *
     * @return checksum value
     */
    public String getChecksum() {
        return checksum;
    }
} 
