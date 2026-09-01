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

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.net.URI;

/**
 * Validates incoming HTTP requests to prevent Path Traversal attacks (CWE-22) 
 * at the edge of the S3 Gateway.
 * 
 * If a request URI contains dangerous sequences like "../" or starts with 
 * double slashes, it is immediately rejected with a 400 Bad Request before 
 * reaching any internal controllers or the core file system model.
 */
@Filter("/**")
public class SecurityValidationFilter implements HttpServerFilter {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityValidationFilter.class);

    /**
     * Intercepts incoming HTTP requests to validate and enforce path-traversal security rules.
     *
     * @param request the incoming HTTP request
     * @param chain server filter execution chain
     * @return publisher returning the HTTP response (or 400 Bad Request if validation fails)
     */
    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        URI uri = request.getUri();
        String path = uri.getPath();

        if (path != null) {
            // Path traversal protection
            if (path.contains("..") || path.contains("//") || path.contains("./")) {
                LOG.warn("SECURITY ALERT: Detected potential path traversal attempt. Rejected URI: {}", path);
                return Flux.just(HttpResponse.status(HttpStatus.BAD_REQUEST).body("Invalid path structure"));
            }
        }

        return chain.proceed(request);
    }
}
