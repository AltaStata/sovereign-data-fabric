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

import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;

/**
 * A diagnostic HTTP filter that logs the method, URI, and headers of every incoming request.
 * <p>
 * This filter is incredibly useful for debugging S3 client issues, especially for complex
 * problems related to signature validation where it's necessary to see the exact headers
 * (like {@code Host}, {@code Authorization}, and {@code x-amz-*}) being sent by the client.
 * <p>
 * It is matched to all routes via the {@code @Filter("/**")} annotation. For production use,
 * it might be advisable to enable this filter conditionally based on a configuration property.
 */
@Filter("/**")
public class RequestLoggingFilter implements HttpServerFilter {

    private static final Logger LOG = LoggerFactory.getLogger(RequestLoggingFilter.class);

    /**
     * Intercepts and logs incoming HTTP request methods, URIs, and headers.
     *
     * @param request the incoming HTTP request
     * @param chain server filter execution chain
     * @return publisher returning the HTTP response
     */
    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        LOG.info("ROUTING_DEBUG: Incoming request {} {}", request.getMethod(), request.getUri());
        request.getHeaders().forEach((name, values) -> {
            LOG.info("ROUTING_DEBUG: Header: {} = {}", name, String.join(", ", values));
        });
        return chain.proceed(request);
    }
}
