/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for JWT authentication filter.
 */
public class JwtAuthenticationFilterTest {

    private ClientRequestContext requestContext;
    private MultivaluedMap<String, Object> headers;

    @BeforeEach
    public void setUp() {
        requestContext = mock(ClientRequestContext.class);
        headers = new MultivaluedHashMap<>();
        when(requestContext.getHeaders()).thenReturn(headers);
    }

    @Test
    public void testJwtDisabledByDefault() {
        // Given
        clearSystemProperties();
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter();

        // When
        filter.filter(requestContext);

        // Then
        assertFalse(filter.isJwtEnabled(), "JWT should be disabled by default");
        assertTrue(headers.isEmpty(), "No headers should be added when JWT is disabled");
    }

    @Test
    public void testJwtEnabledViaSystemProperty() {
        // Given
        System.setProperty("lra.coordinator.jwt.enabled", "true");
        System.setProperty("lra.coordinator.jwt.token", "test-token");
        try {
            JwtAuthenticationFilter filter = new JwtAuthenticationFilter();

            // When
            filter.filter(requestContext);

            // Then
            assertTrue(filter.isJwtEnabled(), "JWT should be enabled");
            assertEquals(headers.getFirst(HttpHeaders.AUTHORIZATION), "Bearer test-token",
                    "Authorization header should be set");
        } finally {
            clearSystemProperties();
        }
    }

    @Test
    public void testCustomHeaderAndPrefix() {
        // Given
        System.setProperty("lra.coordinator.jwt.enabled", "true");
        System.setProperty("lra.coordinator.jwt.token", "custom-token");
        System.setProperty("lra.coordinator.jwt.header", "X-Auth");
        System.setProperty("lra.coordinator.jwt.prefix", "JWT ");
        try {
            JwtAuthenticationFilter filter = new JwtAuthenticationFilter();

            // When
            filter.filter(requestContext);

            // Then
            assertEquals(headers.getFirst("X-Auth"), "JWT custom-token", "Custom header should be used");
            assertNull(headers.getFirst(HttpHeaders.AUTHORIZATION), "Default Authorization header should not be set");
        } finally {
            clearSystemProperties();
        }
    }

    @Test
    public void testSkipWhenHeaderAlreadySet() {
        // Given
        System.setProperty("lra.coordinator.jwt.enabled", "true");
        System.setProperty("lra.coordinator.jwt.token", "test-token");
        headers.putSingle(HttpHeaders.AUTHORIZATION, "Existing auth-value");
        try {
            JwtAuthenticationFilter filter = new JwtAuthenticationFilter();

            // When
            filter.filter(requestContext);

            // Then
            assertEquals(headers.getFirst(HttpHeaders.AUTHORIZATION), "Existing auth-value",
                    "Existing header should not be overwritten");
        } finally {
            clearSystemProperties();
        }
    }

    @Test
    public void testEmptyTokenIsIgnored() {
        // Given
        System.setProperty("lra.coordinator.jwt.enabled", "true");
        System.setProperty("lra.coordinator.jwt.token", "  "); // whitespace only
        try {
            JwtAuthenticationFilter filter = new JwtAuthenticationFilter();

            // When
            filter.filter(requestContext);

            // Then
            assertTrue(filter.isJwtEnabled(), "JWT should be enabled");
            assertNull(headers.getFirst(HttpHeaders.AUTHORIZATION), "No header should be added for empty token");
        } finally {
            clearSystemProperties();
        }
    }

    @Test
    public void testJwtUtilsCreateFilterIfEnabled() {
        // Given - JWT disabled
        clearSystemProperties();

        // When
        var filter = JwtAuthenticationUtils.createJwtFilterIfEnabled();

        // Then
        assertNull(filter, "Filter should not be created when JWT is disabled");

        // Given - JWT enabled
        System.setProperty("lra.coordinator.jwt.enabled", "true");
        try {
            // When
            filter = JwtAuthenticationUtils.createJwtFilterIfEnabled();

            // Then
            assertNotNull(filter, "Filter should be created when JWT is enabled");
        } finally {
            clearSystemProperties();
        }
    }

    @Test
    public void testJwtUtilsCreateFilterWithToken() {
        // Given
        String token = "explicit-token";

        System.setProperty("lra.coordinator.jwt.enabled", "true");
        // When
        var filter = JwtAuthenticationUtils.createJwtFilter(token);

        // Then
        assertNotNull(filter, "Filter should be created with explicit token");

        // Test the filter works
        try {
            filter.filter(requestContext);
        } catch (IOException e) {
            e.printStackTrace();
            fail();
        }
        assertEquals(headers.getFirst(HttpHeaders.AUTHORIZATION), "Bearer explicit-token",
                "Token should be used in header");
    }

    private void clearSystemProperties() {
        System.clearProperty("lra.coordinator.jwt.enabled");
        System.clearProperty("lra.coordinator.jwt.token");
        System.clearProperty("lra.coordinator.jwt.header");
        System.clearProperty("lra.coordinator.jwt.prefix");
    }
}
