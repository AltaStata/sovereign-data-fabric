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
import io.micronaut.http.filter.ServerFilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class SecurityValidationFilterTest {

    private SecurityValidationFilter filter;

    @Mock
    private HttpRequest<?> request;

    @Mock
    private ServerFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new SecurityValidationFilter();
    }

    @Test
    void testValidPathProceeds() throws Exception {
        when(request.getUri()).thenReturn(new URI("/valid/path/file.txt"));
        
        Publisher<MutableHttpResponse<?>> mockResponse = Flux.just(HttpResponse.ok());
        when(chain.proceed(any())).thenReturn(mockResponse);

        Publisher<MutableHttpResponse<?>> result = filter.doFilter(request, chain);
        List<MutableHttpResponse<?>> resultList = Flux.from(result).collectList().block();

        assertEquals(1, resultList.size());
        assertEquals(HttpStatus.OK, resultList.get(0).status());
        verify(chain, times(1)).proceed(request);
    }

    @Test
    void testPathTraversalDotDotRejected() throws Exception {
        when(request.getUri()).thenReturn(new URI("/invalid/../path"));

        Publisher<MutableHttpResponse<?>> result = filter.doFilter(request, chain);
        List<MutableHttpResponse<?>> resultList = Flux.from(result).collectList().block();

        assertEquals(1, resultList.size());
        assertEquals(HttpStatus.BAD_REQUEST, resultList.get(0).status());
        verify(chain, never()).proceed(any());
    }

    @Test
    void testPathTraversalDoubleSlashRejected() throws Exception {
        when(request.getUri()).thenReturn(new URI("/invalid//path"));

        Publisher<MutableHttpResponse<?>> result = filter.doFilter(request, chain);
        List<MutableHttpResponse<?>> resultList = Flux.from(result).collectList().block();

        assertEquals(1, resultList.size());
        assertEquals(HttpStatus.BAD_REQUEST, resultList.get(0).status());
        verify(chain, never()).proceed(any());
    }

    @Test
    void testPathTraversalDotSlashRejected() throws Exception {
        when(request.getUri()).thenReturn(new URI("/invalid/./path"));

        Publisher<MutableHttpResponse<?>> result = filter.doFilter(request, chain);
        List<MutableHttpResponse<?>> resultList = Flux.from(result).collectList().block();

        assertEquals(1, resultList.size());
        assertEquals(HttpStatus.BAD_REQUEST, resultList.get(0).status());
        verify(chain, never()).proceed(any());
    }
}
