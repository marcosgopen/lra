/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client;

import static org.junit.jupiter.api.Assertions.*;

import io.narayana.lra.client.internal.JwtAuthenticationUtils;
import io.narayana.lra.client.internal.NarayanaLRAClient;
import jakarta.ws.rs.client.ClientRequestFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the JWT integration examples to verify they work correctly.
 */
public class JwtIntegrationExampleTest {

    @BeforeEach
    public void setUp() {
        clearSystemProperties();
    }

    @AfterEach
    public void tearDown() {
        clearSystemProperties();
    }

    @Test
    public void testConfigBasedExample() {
        // Given - JWT disabled by default
        NarayanaLRAClient client = new NarayanaLRAClient("https://coordinator.example.com/lra-coordinator");

        // When - JWT not enabled
        ClientRequestFilter jwtFilter = JwtAuthenticationUtils.createJwtFilterIfEnabled();

        // Then - no filter should be created
        assertNull(jwtFilter, "Filter should not be created when JWT is disabled");

        // Given - JWT enabled via configuration
        System.setProperty("lra.coordinator.jwt.enabled", "true");
        System.setProperty("lra.coordinator.jwt.token", "test-token");

        // When
        jwtFilter = JwtAuthenticationUtils.createJwtFilterIfEnabled();
        client.setAuthenticationFilter(jwtFilter);

        // Then
        assertNotNull(jwtFilter, "Filter should be created when JWT is enabled");
        assertSame(jwtFilter, client.getAuthenticationFilter(), "Client should have the JWT filter set");
    }

    @Test
    public void testExplicitTokenExample() {
        // Given
        String jwtToken = "explicit-jwt-token";
        NarayanaLRAClient client = new NarayanaLRAClient("https://coordinator.example.com/lra-coordinator");

        // When
        ClientRequestFilter jwtFilter = JwtAuthenticationUtils.createJwtFilter(jwtToken);
        client.setAuthenticationFilter(jwtFilter);

        // Then
        assertNotNull(jwtFilter, "Filter should be created with explicit token");
        assertSame(jwtFilter, client.getAuthenticationFilter(), "Client should have the JWT filter set");
    }

    @Test
    public void testCdiInjectedExample() {
        // Given - simulate CDI environment
        System.setProperty("lra.coordinator.jwt.enabled", "true");
        NarayanaLRAClient client = new NarayanaLRAClient("https://coordinator.example.com/lra-coordinator");

        // When
        ClientRequestFilter jwtFilter = JwtAuthenticationUtils.createJwtFilterIfEnabled();

        // Then
        assertNotNull(jwtFilter, "Filter should be created when JWT is enabled");

        // When setting on client
        client.setAuthenticationFilter(jwtFilter);

        // Then
        assertSame(jwtFilter, client.getAuthenticationFilter(), "Client should have the JWT filter set");
    }

    @Test
    public void testConditionalExample() {
        // Given - production coordinator URL
        String prodCoordinatorUrl = "https://production.example.com/lra-coordinator";
        String devCoordinatorUrl = "https://dev.example.com/lra-coordinator";

        // Test production scenario
        NarayanaLRAClient prodClient = new NarayanaLRAClient(prodCoordinatorUrl);

        if (prodCoordinatorUrl.contains("production.example.com")) {
            System.setProperty("lra.coordinator.jwt.enabled", "true");
            System.setProperty("lra.coordinator.jwt.token", "prod-token");
            ClientRequestFilter jwtFilter = JwtAuthenticationUtils.createJwtFilterIfEnabled();
            prodClient.setAuthenticationFilter(jwtFilter);

            // Then
            assertNotNull(prodClient.getAuthenticationFilter(), "Production client should have JWT filter");
        }

        // Test development scenario
        clearSystemProperties(); // Reset for dev test
        NarayanaLRAClient devClient = new NarayanaLRAClient(devCoordinatorUrl);

        if (!devCoordinatorUrl.contains("production.example.com")) {
            // No JWT configuration for dev
            ClientRequestFilter jwtFilter = JwtAuthenticationUtils.createJwtFilterIfEnabled();
            devClient.setAuthenticationFilter(jwtFilter);

            // Then
            assertNull(devClient.getAuthenticationFilter(),
                    "Development client should not have JWT filter when disabled");
        }
    }

    @Test
    public void testJwtFilterConfiguration() {
        // Test with different JWT configurations
        System.setProperty("lra.coordinator.jwt.enabled", "true");
        System.setProperty("lra.coordinator.jwt.token", "test-token");
        System.setProperty("lra.coordinator.jwt.header", "X-Auth");
        System.setProperty("lra.coordinator.jwt.prefix", "JWT ");

        // When
        ClientRequestFilter jwtFilter = JwtAuthenticationUtils.createJwtFilterIfEnabled();

        // Then
        assertNotNull(jwtFilter, "Filter should be created with custom configuration");
    }

    @Test
    public void testJwtTokenUpdate() {
        // Given
        NarayanaLRAClient client = new NarayanaLRAClient("https://coordinator.example.com/lra-coordinator");

        // When - set initial token
        String initialToken = "initial-token";
        ClientRequestFilter initialFilter = JwtAuthenticationUtils.createJwtFilter(initialToken);
        client.setAuthenticationFilter(initialFilter);

        // Then
        assertNotNull(client.getAuthenticationFilter(), "Initial filter should be set");

        // When - update token
        String updatedToken = "updated-token";
        ClientRequestFilter updatedFilter = JwtAuthenticationUtils.createJwtFilter(updatedToken);
        client.setAuthenticationFilter(updatedFilter);

        // Then
        assertNotNull(client.getAuthenticationFilter(), "Updated filter should be set");
        assertNotSame(initialFilter, client.getAuthenticationFilter(), "Filter should be different after update");
    }

    @Test
    public void testRemoveJwtAuthentication() {
        // Given - client with JWT authentication
        NarayanaLRAClient client = new NarayanaLRAClient("https://coordinator.example.com/lra-coordinator");
        ClientRequestFilter jwtFilter = JwtAuthenticationUtils.createJwtFilter("test-token");
        client.setAuthenticationFilter(jwtFilter);

        // Verify JWT is set
        assertNotNull(client.getAuthenticationFilter(), "JWT filter should be set initially");

        // When - remove JWT authentication
        client.setAuthenticationFilter(null);

        // Then
        assertNull(client.getAuthenticationFilter(), "JWT filter should be removed");
    }

    private void clearSystemProperties() {
        System.clearProperty("lra.coordinator.jwt.enabled");
        System.clearProperty("lra.coordinator.jwt.token");
        System.clearProperty("lra.coordinator.jwt.header");
        System.clearProperty("lra.coordinator.jwt.prefix");
    }
}
