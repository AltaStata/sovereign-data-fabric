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
 * AWS unsigned chunked decoding input stream
 * Based on S3Mock implementation
 */
package com.altastata.s3gateway.util;

import java.io.IOException;
import java.io.InputStream;

/**
 * Merges chunks from AWS chunked AwsUnsignedChunkedEncodingInputStream.
 * The checksum is optionally included in the stream as part of the "trail headers"
 * after the last chunk.
 *
 * <p>The original stream looks like this:</p>
 *
 * <pre>
 * 24
 * ## sample test file ##
 *
 * demo=content
 * 0
 * x-amz-checksum-sha256:1VcEifAruhjVvjzul4sC0B1EmlUdzqvsp6BP0KSVdTE=
 * </pre>
 *
 * <p>The format of each chunk of data is:</p>
 *
 * <pre>
 * [hex-encoded-number-of-bytes-in-chunk][EOL]
 * [payload-bytes-of-this-chunk][EOL]
 * </pre>
 *
 * <p>The format of the full payload is:</p>
 *
 * <pre>
 * [hex-encoded-number-of-bytes-in-chunk][EOL]
 * [payload-bytes-of-this-chunk][EOL]
 * 0[EOL]
 * x-amz-checksum-[checksum-algoritm]:[checksum][EOL]
 * [other trail headers]
 * </pre>
 */
public class AwsUnsignedChunkedDecodingChecksumInputStream extends AbstractAwsInputStream {

    /**
     * Constructs AwsUnsignedChunkedDecodingChecksumInputStream with the given source and length.
     *
     * @param source source input stream
     * @param decodedLength decoded stream length in bytes
     */
    public AwsUnsignedChunkedDecodingChecksumInputStream(InputStream source, long decodedLength) {
        super(source, decodedLength);
    }

    /**
     * Reads a single byte from the decoded stream, automatically parsing chunk headers.
     *
     * @return next byte or -1 if end of stream reached
     * @throws IOException if any I/O errors occur
     */
    @Override
    public int read() throws IOException {
        if (chunkLength == 0L) {
            //try to read chunk length
            var hexLengthBytes = readHexlength();
            if (hexLengthBytes.length == 0) {
                return -1;
            }

            setChunkLength(hexLengthBytes);

            if (chunkLength == 0L) {
                //chunk length found, but was "0". Try and find the checksum.
                extractAlgorithmAndChecksum();
                return -1;
            }

            chunks++;
        }

        readDecodedLength++;
        chunkLength--;

        return source.read();
    }

    /**
     * Reads hex length portion of the next chunk header.
     *
     * @return hex length bytes read
     * @throws IOException if any I/O errors occur
     */
    private byte[] readHexlength() throws IOException {
        var hexLengthBytes = readUntil(CRLF);
        if (hexLengthBytes.length == 0) {
            hexLengthBytes = readUntil(CRLF);
        }
        return hexLengthBytes;
    }
} 
