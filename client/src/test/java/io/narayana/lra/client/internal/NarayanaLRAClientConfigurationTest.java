/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client.internal;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.ws.rs.client.ClientRequestFilter;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for NarayanaLRAClient configuration and initialization functionality.
 */
public class NarayanaLRAClientConfigurationTest {

    @BeforeEach
    public void setUp() {
        clearSystemProperties();
    }

    @AfterEach
    public void tearDown() {
        clearSystemProperties();
    }

    @Test
    public void testBasicClientCreation() {
        // When
        NarayanaLRAClient client = new NarayanaLRAClient("https://coordinator.example.com/lra-coordinator");

        // Then
        assertNotNull(client, "Client should be created");
        assertNull(client.getAuthenticationFilter(), "Client should start without authentication filter");
        assertNull(client.getSslContext(), "Client should start without custom SSL context");
        assertTrue(client.isHostnameVerificationEnabled(), "Hostname verification should be enabled by default");
    }

    @Test
    public void testAuthenticationFilterConfiguration() {
        // Given
        NarayanaLRAClient client = new NarayanaLRAClient("https://coordinator.example.com/lra-coordinator");
        ClientRequestFilter jwtFilter = JwtAuthenticationUtils.createJwtFilter("test-token");

        // When
        client.setAuthenticationFilter(jwtFilter);

        // Then
        assertSame(jwtFilter, client.getAuthenticationFilter(), "Authentication filter should be set");

        // When - remove authentication
        client.setAuthenticationFilter(null);

        // Then
        assertNull(client.getAuthenticationFilter(), "Authentication filter should be removed");
    }

    @Test
    public void testSslContextConfiguration() throws Exception {
        // Given
        NarayanaLRAClient client = new NarayanaLRAClient("https://coordinator.example.com/lra-coordinator");
        SSLContext sslContext = SslUtils.createDefaultSslContext();

        // When
        client.setSslContext(sslContext);

        // Then
        assertSame(sslContext, client.getSslContext(), "SSL context should be set");

        // When - remove SSL context
        client.setSslContext(null);

        // Then
        assertNull(client.getSslContext(), "SSL context should be removed");
    }

    @Test
    public void testHostnameVerificationConfiguration() {
        // Given
        NarayanaLRAClient client = new NarayanaLRAClient("https://coordinator.example.com/lra-coordinator");

        // Initially enabled by default
        assertTrue(client.isHostnameVerificationEnabled(), "Hostname verification should be enabled by default");

        // When
        client.setHostnameVerificationEnabled(false);

        // Then
        assertFalse(client.isHostnameVerificationEnabled(), "Hostname verification should be disabled");

        // When
        client.setHostnameVerificationEnabled(true);

        // Then
        assertTrue(client.isHostnameVerificationEnabled(), "Hostname verification should be enabled");
    }

    @Test
    public void testReinitializeRestClient() throws Exception {
        // Given
        NarayanaLRAClient client = new NarayanaLRAClient("https://coordinator.example.com/lra-coordinator");

        // Set some configuration
        ClientRequestFilter jwtFilter = JwtAuthenticationUtils.createJwtFilter("test-token");
        SSLContext sslContext = SslUtils.createDefaultSslContext();

        client.setAuthenticationFilter(jwtFilter);
        client.setSslContext(sslContext);
        client.setHostnameVerificationEnabled(false);

        // When - manually reinitialize
        client.reinitializeRestClient();

        // Then - configuration should remain
        assertSame(jwtFilter, client.getAuthenticationFilter(), "Authentication filter should remain");
        assertSame(sslContext, client.getSslContext(), "SSL context should remain");
        assertFalse(client.isHostnameVerificationEnabled(), "Hostname verification should remain disabled");
    }

    @Test
    public void testConfigurationPersistenceAcrossReinitialization() throws Exception {
        // Given
        NarayanaLRAClient client = new NarayanaLRAClient("https://coordinator.example.com/lra-coordinator");

        // Set comprehensive configuration
        ClientRequestFilter jwtFilter = JwtAuthenticationUtils.createJwtFilter("persistent-token");
        SSLContext sslContext = SslUtils.createTrustAllSslContext();

        // When - set configuration (each setter triggers reinitialization)
        client.setAuthenticationFilter(jwtFilter);
        client.setSslContext(sslContext);
        client.setHostnameVerificationEnabled(false);

        // Store references
        ClientRequestFilter storedFilter = client.getAuthenticationFilter();
        SSLContext storedSslContext = client.getSslContext();
        boolean storedHostnameVerification = client.isHostnameVerificationEnabled();

        // When - explicit reinitialization
        client.reinitializeRestClient();

        // Then - all configuration should persist
        assertSame(storedFilter, client.getAuthenticationFilter(), "JWT filter should persist");
        assertSame(storedSslContext, client.getSslContext(), "SSL context should persist");
        assertEquals(storedHostnameVerification, client.isHostnameVerificationEnabled(),
                "Hostname verification should persist");
    }

    @Test
    public void testMultipleConfigurationUpdates() throws Exception {
        // Given
        NarayanaLRAClient client = new NarayanaLRAClient("https://coordinator.example.com/lra-coordinator");

        // Test multiple authentication updates
        for (int i = 1; i <= 3; i++) {
            ClientRequestFilter filter = JwtAuthenticationUtils.createJwtFilter("token-" + i);
            client.setAuthenticationFilter(filter);

            assertSame(filter, client.getAuthenticationFilter(), "Filter " + i + " should be set");
        }

        // Test multiple SSL context updates
        SSLContext defaultContext = SslUtils.createDefaultSslContext();
        SSLContext trustAllContext = SslUtils.createTrustAllSslContext();

        client.setSslContext(defaultContext);
        assertSame(defaultContext, client.getSslContext(), "Default SSL context should be set");

        client.setSslContext(trustAllContext);
        assertSame(trustAllContext, client.getSslContext(), "Trust-all SSL context should be set");

        // Test hostname verification toggles
        client.setHostnameVerificationEnabled(false);
        assertFalse(client.isHostnameVerificationEnabled(), "Hostname verification should be disabled");

        client.setHostnameVerificationEnabled(true);
        assertTrue(client.isHostnameVerificationEnabled(), "Hostname verification should be enabled");
    }

    @Test
    public void testConfigurationWithSystemProperties() {
        // Given - SSL configuration via system properties
        System.setProperty("lra.coordinator.ssl.verify-hostname", "false");

        // When
        NarayanaLRAClient client = new NarayanaLRAClient("https://coordinator.example.com/lra-coordinator");

        // Then - default hostname verification should still apply programmatically
        // (system properties are used during SSL context creation, not for the field)
        assertTrue(client.isHostnameVerificationEnabled(),
                "Programmatic hostname verification should be enabled by default");

        // When - explicitly set based on system property simulation
        String verifyProperty = System.getProperty("lra.coordinator.ssl.verify-hostname", "true");
        boolean shouldVerify = "true".equals(verifyProperty);
        client.setHostnameVerificationEnabled(shouldVerify);

        // Then
        assertEquals(shouldVerify, client.isHostnameVerificationEnabled(),
                "Hostname verification should match system property");
    }

    @Test
    public void testJwtConfigurationFromProperties() {
        // Given - JWT configuration
        System.setProperty("lra.coordinator.jwt.enabled", "true");
        System.setProperty("lra.coordinator.jwt.token", "system-property-token");

        try {
            NarayanaLRAClient client = new NarayanaLRAClient("https://coordinator.example.com/lra-coordinator");

            // When
            ClientRequestFilter jwtFilter = JwtAuthenticationUtils.createJwtFilterIfEnabled();
            client.setAuthenticationFilter(jwtFilter);

            // Then
            assertNotNull(jwtFilter, "JWT filter should be created from system properties");
            assertSame(jwtFilter, client.getAuthenticationFilter(), "JWT filter should be set on client");

        } finally {
            clearSystemProperties();
        }
    }

    @Test
    public void testClientWithDifferentCoordinatorUrls() {
        // Test that clients with different URLs work independently
        NarayanaLRAClient client1 = new NarayanaLRAClient("https://coordinator1.example.com/lra-coordinator");
        NarayanaLRAClient client2 = new NarayanaLRAClient("https://coordinator2.example.com/lra-coordinator");

        // Configure differently
        ClientRequestFilter filter1 = JwtAuthenticationUtils.createJwtFilter("token1");
        ClientRequestFilter filter2 = JwtAuthenticationUtils.createJwtFilter("token2");

        client1.setAuthenticationFilter(filter1);
        client1.setHostnameVerificationEnabled(true);

        client2.setAuthenticationFilter(filter2);
        client2.setHostnameVerificationEnabled(false);

        // Verify independence
        assertSame(filter1, client1.getAuthenticationFilter(), "Client1 should have filter1");
        assertSame(filter2, client2.getAuthenticationFilter(), "Client2 should have filter2");

        assertTrue(client1.isHostnameVerificationEnabled(), "Client1 should have hostname verification enabled");
        assertFalse(client2.isHostnameVerificationEnabled(), "Client2 should have hostname verification disabled");
    }

    @Test
    public void testClientCreationWithDifferentConstructors() {
        // Test different constructor patterns

        // Default constructor
        NarayanaLRAClient client1 = new NarayanaLRAClient();
        assertNotNull(client1, "Default constructor should work");

        // String URL constructor
        NarayanaLRAClient client2 = new NarayanaLRAClient("https://coordinator.example.com/lra-coordinator");
        assertNotNull(client2, "String URL constructor should work");

        // URI constructor
        java.net.URI coordinatorUri = java.net.URI.create("https://coordinator.example.com/lra-coordinator");
        NarayanaLRAClient client3 = new NarayanaLRAClient(coordinatorUri);
        assertNotNull(client3, "URI constructor should work");

        // All should start with same default configuration
        for (NarayanaLRAClient client : new NarayanaLRAClient[] { client1, client2, client3 }) {
            assertNull(client.getAuthenticationFilter(), "Should start without authentication");
            assertNull(client.getSslContext(), "Should start without SSL context");
            assertTrue(client.isHostnameVerificationEnabled(), "Should start with hostname verification enabled");
        }
    }

    private void clearSystemProperties() {
        System.clearProperty("lra.coordinator.ssl.truststore.path");
        System.clearProperty("lra.coordinator.ssl.truststore.password");
        System.clearProperty("lra.coordinator.ssl.truststore.type");
        System.clearProperty("lra.coordinator.ssl.keystore.path");
        System.clearProperty("lra.coordinator.ssl.keystore.password");
        System.clearProperty("lra.coordinator.ssl.keystore.type");
        System.clearProperty("lra.coordinator.ssl.verify-hostname");
        System.clearProperty("lra.coordinator.jwt.enabled");
        System.clearProperty("lra.coordinator.jwt.token");
        System.clearProperty("lra.coordinator.jwt.header");
        System.clearProperty("lra.coordinator.jwt.prefix");
    }
}
