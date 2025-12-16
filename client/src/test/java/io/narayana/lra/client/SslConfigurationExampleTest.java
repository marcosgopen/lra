/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.narayana.lra.client.internal.NarayanaLRAClient;
import io.narayana.lra.client.internal.SslUtils;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the SSL configuration examples to verify they work correctly.
 */
public class SslConfigurationExampleTest {

    @BeforeEach
    public void setUp() {
        clearSystemProperties();
    }

    @AfterEach
    public void tearDown() {
        clearSystemProperties();
    }

    @Test
    public void testConfigBasedSslExample() {
        // Given - SSL configuration via properties (simulated)
        System.setProperty("lra.coordinator.ssl.truststore.path", "/nonexistent/truststore.jks");
        System.setProperty("lra.coordinator.ssl.truststore.password", "password");
        System.setProperty("lra.coordinator.ssl.truststore.type", "JKS");
        System.setProperty("lra.coordinator.ssl.verify-hostname", "true");

        // When
        NarayanaLRAClient client = new NarayanaLRAClient("https://secure-coordinator.example.com/lra-coordinator");

        // Then - client should be created successfully
        assertNotNull(client, "Client should be created with SSL configuration");
        assertTrue(client.isHostnameVerificationEnabled(), "Hostname verification should be enabled by default");

        // Note: The actual SSL context creation will fail due to nonexistent truststore,
        // but the client initialization should handle this gracefully
    }

    @Test
    public void testProgrammaticSslExample() throws Exception {
        // Given
        NarayanaLRAClient client = new NarayanaLRAClient("https://secure-coordinator.example.com/lra-coordinator");

        // When - create default SSL context (should work without external files)
        SSLContext sslContext = SslUtils.createDefaultSslContext();
        client.setSslContext(sslContext);

        // Then
        assertNotNull(client.getSslContext(), "SSL context should be set");
        assertSame(sslContext, client.getSslContext(), "SSL context should be the one we set");

        // When - disable hostname verification
        client.setHostnameVerificationEnabled(false);

        // Then
        assertFalse(client.isHostnameVerificationEnabled(), "Hostname verification should be disabled");
    }

    @Test
    public void testTrustAllSslExample() throws Exception {
        // Given
        NarayanaLRAClient client = new NarayanaLRAClient("https://dev-coordinator.example.com/lra-coordinator");

        // When - create trust-all SSL context
        SSLContext trustAllContext = SslUtils.createTrustAllSslContext();
        client.setSslContext(trustAllContext);
        client.setHostnameVerificationEnabled(false);

        // Then
        assertNotNull(trustAllContext, "Trust-all SSL context should be created");
        assertSame(trustAllContext, client.getSslContext(), "SSL context should be set on client");
        assertFalse(client.isHostnameVerificationEnabled(), "Hostname verification should be disabled");
    }

    @Test
    public void testSslContextWithTruststore() {
        // Test creating SSL context with truststore (will fail with nonexistent file, but should not throw)
        try {
            SSLContext sslContext = SslUtils.createSslContextWithTruststore(
                    "/nonexistent/truststore.jks",
                    "password",
                    "JKS");
            fail("Should have thrown exception for nonexistent truststore");
        } catch (Exception e) {
            // Expected - truststore file doesn't exist
            assertTrue(
                    e.getMessage().contains("FileNotFoundException") ||
                            e.getMessage().contains("No such file") ||
                            e instanceof java.io.FileNotFoundException,
                    "Should get a file-related exception");
        }
    }

    @Test
    public void testMutualTlsSslContext() {
        // Test creating mutual TLS SSL context (will fail with nonexistent files, but should not throw unexpected exceptions)
        try {
            SSLContext sslContext = SslUtils.createMutualTlsSslContext(
                    "/nonexistent/truststore.jks", "trust-password", "JKS",
                    "/nonexistent/keystore.jks", "key-password", "JKS");
            fail("Should have thrown exception for nonexistent keystores");
        } catch (Exception e) {
            // Expected - keystore files don't exist
            assertTrue(
                    e.getMessage().contains("FileNotFoundException") ||
                            e.getMessage().contains("No such file") ||
                            e instanceof java.io.FileNotFoundException,
                    "Should get a file-related exception");
        }
    }

    @Test
    public void testSslSettingsUpdate() throws Exception {
        // Given
        NarayanaLRAClient client = new NarayanaLRAClient("https://coordinator.example.com/lra-coordinator");

        // When - set initial SSL context
        SSLContext initialContext = SslUtils.createDefaultSslContext();
        client.setSslContext(initialContext);
        client.setHostnameVerificationEnabled(true);

        // Then
        assertSame(initialContext, client.getSslContext(), "Initial SSL context should be set");
        assertTrue(client.isHostnameVerificationEnabled(), "Hostname verification should be enabled");

        // When - update SSL context
        SSLContext trustAllContext = SslUtils.createTrustAllSslContext();
        client.setSslContext(trustAllContext);
        client.setHostnameVerificationEnabled(false);

        // Then
        assertSame(trustAllContext, client.getSslContext(), "SSL context should be updated");
        assertFalse(client.isHostnameVerificationEnabled(), "Hostname verification should be disabled");
    }

    @Test
    public void testCombinedJwtAndSslExample() throws Exception {
        // Given
        NarayanaLRAClient client = new NarayanaLRAClient("https://secure-coordinator.example.com/lra-coordinator");

        // When - configure SSL
        SSLContext sslContext = SslUtils.createDefaultSslContext();
        client.setSslContext(sslContext);

        // And configure JWT
        System.setProperty("lra.coordinator.jwt.enabled", "true");
        System.setProperty("lra.coordinator.jwt.token", "test-token");

        var jwtFilter = io.narayana.lra.client.internal.JwtAuthenticationUtils.createJwtFilterIfEnabled();
        client.setAuthenticationFilter(jwtFilter);

        // Then
        assertNotNull(client.getSslContext(), "SSL context should be set");
        assertNotNull(client.getAuthenticationFilter(), "JWT filter should be set");
    }

    @Test
    public void testEnvironmentBasedConfiguration() throws Exception {
        // Test production environment
        System.setProperty("environment", "production");
        NarayanaLRAClient prodClient = new NarayanaLRAClient("https://prod-coordinator.example.com/lra-coordinator");

        String environment = System.getProperty("environment");
        if ("production".equals(environment)) {
            // Use default SSL context since we don't have real truststore files
            SSLContext prodContext = SslUtils.createDefaultSslContext();
            prodClient.setSslContext(prodContext);
            prodClient.setHostnameVerificationEnabled(true);

            assertNotNull(prodClient.getSslContext(), "Production SSL context should be set");
            assertTrue(prodClient.isHostnameVerificationEnabled(), "Production should have hostname verification enabled");
        }

        // Test development environment
        System.setProperty("environment", "development");
        NarayanaLRAClient devClient = new NarayanaLRAClient("https://dev-coordinator.example.com/lra-coordinator");

        environment = System.getProperty("environment");
        if ("development".equals(environment)) {
            SSLContext trustAllContext = SslUtils.createTrustAllSslContext();
            devClient.setSslContext(trustAllContext);
            devClient.setHostnameVerificationEnabled(false);

            assertNotNull(devClient.getSslContext(), "Development SSL context should be set");
            assertFalse(devClient.isHostnameVerificationEnabled(), "Development should have hostname verification disabled");
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
        System.clearProperty("environment");
    }
}
