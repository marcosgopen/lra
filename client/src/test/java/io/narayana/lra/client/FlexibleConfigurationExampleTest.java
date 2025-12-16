/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client;

import static org.junit.jupiter.api.Assertions.*;

import io.narayana.lra.client.internal.JwtAuthenticationUtils;
import io.narayana.lra.client.internal.NarayanaLRAClient;
import io.narayana.lra.client.internal.SslUtils;
import jakarta.ws.rs.client.ClientRequestFilter;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the flexible configuration examples to verify they work correctly.
 */
public class FlexibleConfigurationExampleTest {

    @BeforeEach
    public void setUp() {
        clearSystemProperties();
    }

    @AfterEach
    public void tearDown() {
        clearSystemProperties();
    }

    @Test
    public void testDynamicAuthenticationExample() {
        // Given
        NarayanaLRAClient client = new NarayanaLRAClient("https://coordinator.example.com/lra-coordinator");

        // Initially no authentication
        assertNull(client.getAuthenticationFilter(), "Client should start without authentication");

        // When - add JWT authentication
        String jwtToken = "initial-jwt-token";
        ClientRequestFilter jwtFilter = JwtAuthenticationUtils.createJwtFilter(jwtToken);
        client.setAuthenticationFilter(jwtFilter);

        // Then
        assertNotNull(client.getAuthenticationFilter(), "Client should have JWT authentication");
        assertSame(jwtFilter, client.getAuthenticationFilter(), "JWT filter should be set");

        // When - update with new token
        String refreshedToken = "refreshed-jwt-token";
        ClientRequestFilter newJwtFilter = JwtAuthenticationUtils.createJwtFilter(refreshedToken);
        client.setAuthenticationFilter(newJwtFilter);

        // Then
        assertNotNull(client.getAuthenticationFilter(), "Client should still have authentication");
        assertNotSame(jwtFilter, client.getAuthenticationFilter(), "JWT filter should be updated");
        assertSame(newJwtFilter, client.getAuthenticationFilter(), "New JWT filter should be set");
    }

    @Test
    public void testEnvironmentBasedSslExample() throws Exception {
        // Test production environment
        System.setProperty("app.environment", "production");
        testEnvironmentSpecificSslConfiguration("production", true, true);

        // Test staging environment
        System.setProperty("app.environment", "staging");
        testEnvironmentSpecificSslConfiguration("staging", true, true);

        // Test development environment
        System.setProperty("app.environment", "development");
        testEnvironmentSpecificSslConfiguration("development", true, false);
    }

    private void testEnvironmentSpecificSslConfiguration(String environment, boolean shouldHaveSslContext,
            boolean shouldVerifyHostname) throws Exception {
        // Given
        NarayanaLRAClient client = new NarayanaLRAClient("https://coordinator.example.com/lra-coordinator");
        String envProperty = System.getProperty("app.environment", "development");

        // When - configure based on environment
        switch (envProperty) {
            case "production":
                // Use default SSL context (can't test with real truststore files in unit tests)
                SSLContext prodSslContext = SslUtils.createDefaultSslContext();
                client.setSslContext(prodSslContext);
                client.setHostnameVerificationEnabled(true);
                break;

            case "staging":
                // Use default SSL context for staging
                SSLContext stagingSslContext = SslUtils.createDefaultSslContext();
                client.setSslContext(stagingSslContext);
                client.setHostnameVerificationEnabled(true);
                break;

            case "development":
            default:
                // Trust all certificates for development
                SSLContext trustAllContext = SslUtils.createTrustAllSslContext();
                client.setSslContext(trustAllContext);
                client.setHostnameVerificationEnabled(false);
                break;
        }

        // Then
        if (shouldHaveSslContext) {
            assertNotNull(client.getSslContext(), "Client should have SSL context for " + environment);
        }
        assertEquals(shouldVerifyHostname, client.isHostnameVerificationEnabled(),
                "Hostname verification should match environment expectation for " + environment);
    }

    @Test
    public void testRuntimeSecurityPolicyExample() throws Exception {
        // Given
        NarayanaLRAClient client = new NarayanaLRAClient("https://coordinator.example.com/lra-coordinator");

        // Test high security requirements
        System.setProperty("security.level", "HIGH");
        boolean requiresEnhancedSecurity = "HIGH".equals(System.getProperty("security.level", "NORMAL"));

        if (requiresEnhancedSecurity) {
            // Use default SSL context (can't test mutual TLS with real certificates in unit tests)
            SSLContext sslContext = SslUtils.createDefaultSslContext();
            client.setSslContext(sslContext);

            // Enable JWT authentication
            System.setProperty("lra.coordinator.jwt.enabled", "true");
            System.setProperty("lra.coordinator.jwt.token", "high-security-token");
            ClientRequestFilter jwtFilter = JwtAuthenticationUtils.createJwtFilterIfEnabled();
            client.setAuthenticationFilter(jwtFilter);

            // Then
            assertNotNull(client.getSslContext(), "High security should have SSL context");
            assertNotNull(client.getAuthenticationFilter(), "High security should have JWT authentication");
        }

        // Test normal security requirements
        System.setProperty("security.level", "NORMAL");
        requiresEnhancedSecurity = "HIGH".equals(System.getProperty("security.level", "NORMAL"));

        if (!requiresEnhancedSecurity) {
            // Downgrade security
            SSLContext basicSslContext = SslUtils.createDefaultSslContext();
            client.setSslContext(basicSslContext);
            client.setAuthenticationFilter(null);

            // Then
            assertNotNull(client.getSslContext(), "Normal security should have basic SSL context");
            assertNull(client.getAuthenticationFilter(), "Normal security should not have JWT authentication");
        }
    }

    @Test
    public void testConfigurationHotReloadExample() throws Exception {
        // Given
        NarayanaLRAClient client = new NarayanaLRAClient("https://coordinator.example.com/lra-coordinator");

        // Apply initial configuration
        applyTestConfigurationUpdate(client, "initial");
        SSLContext initialSslContext = client.getSslContext();
        ClientRequestFilter initialAuthFilter = client.getAuthenticationFilter();

        // When - apply configuration update
        applyTestConfigurationUpdate(client, "updated");

        // Then
        assertNotNull(client.getSslContext(), "Updated configuration should have SSL context");
        assertNotNull(client.getAuthenticationFilter(), "Updated configuration should have JWT authentication");
        assertNotSame(initialAuthFilter, client.getAuthenticationFilter(), "Auth filter should be updated");

        // When - apply final configuration
        SSLContext beforeFinalSslContext = client.getSslContext();
        applyTestConfigurationUpdate(client, "final");

        // Then
        assertNotNull(client.getSslContext(), "Final configuration should have SSL context");
        assertNotNull(client.getAuthenticationFilter(), "Final configuration should have JWT authentication");
        assertNotSame(beforeFinalSslContext, client.getSslContext(), "SSL context should be updated");
    }

    @Test
    public void testExplicitReinitializationExample() throws Exception {
        // Given
        NarayanaLRAClient client = new NarayanaLRAClient("https://coordinator.example.com/lra-coordinator");

        // Set initial configuration
        ClientRequestFilter jwtFilter = JwtAuthenticationUtils.createJwtFilter("initial-token");
        SSLContext sslContext = SslUtils.createDefaultSslContext();

        // When - set configuration (each setter triggers reinitialization)
        client.setAuthenticationFilter(jwtFilter);
        client.setSslContext(sslContext);

        // Then
        assertSame(jwtFilter, client.getAuthenticationFilter(), "JWT filter should be set");
        assertSame(sslContext, client.getSslContext(), "SSL context should be set");

        // When - manually reinitialize
        client.reinitializeRestClient();

        // Then - configuration should remain the same
        assertSame(jwtFilter, client.getAuthenticationFilter(), "JWT filter should remain after reinitialization");
        assertSame(sslContext, client.getSslContext(), "SSL context should remain after reinitialization");
    }

    @Test
    public void testMultipleConfigurationUpdates() throws Exception {
        // Given
        NarayanaLRAClient client = new NarayanaLRAClient("https://coordinator.example.com/lra-coordinator");

        // Track configuration changes
        int configurationChanges = 0;

        // When - apply multiple configuration changes
        for (String config : new String[] { "config1", "config2", "config3" }) {
            // Set JWT authentication
            ClientRequestFilter jwtFilter = JwtAuthenticationUtils.createJwtFilter(config + "-token");
            client.setAuthenticationFilter(jwtFilter);

            // Set SSL context
            SSLContext sslContext = config.equals("config3") ? SslUtils.createTrustAllSslContext()
                    : SslUtils.createDefaultSslContext();
            client.setSslContext(sslContext);

            // Set hostname verification
            client.setHostnameVerificationEnabled(!config.equals("config3"));

            configurationChanges++;

            // Verify configuration is applied
            assertNotNull(client.getAuthenticationFilter(), "Configuration " + config + " should have JWT");
            assertNotNull(client.getSslContext(), "Configuration " + config + " should have SSL");

            boolean expectedVerification = !config.equals("config3");
            assertEquals(expectedVerification, client.isHostnameVerificationEnabled(),
                    "Configuration " + config + " hostname verification");
        }

        // Then
        assertEquals(3, configurationChanges, "Should have applied 3 configurations");
        assertNotNull(client.getAuthenticationFilter(), "Final configuration should have authentication");
        assertNotNull(client.getSslContext(), "Final configuration should have SSL context");
        assertFalse(client.isHostnameVerificationEnabled(), "Final configuration should have hostname verification disabled");
    }

    @Test
    public void testConfigurationRollback() throws Exception {
        // Given - client with good configuration
        NarayanaLRAClient client = new NarayanaLRAClient("https://coordinator.example.com/lra-coordinator");

        SSLContext goodSslContext = SslUtils.createDefaultSslContext();
        ClientRequestFilter goodAuthFilter = JwtAuthenticationUtils.createJwtFilter("good-token");

        client.setSslContext(goodSslContext);
        client.setAuthenticationFilter(goodAuthFilter);
        client.setHostnameVerificationEnabled(true);

        // Store good configuration
        SSLContext savedSslContext = client.getSslContext();
        ClientRequestFilter savedAuthFilter = client.getAuthenticationFilter();
        boolean savedHostnameVerification = client.isHostnameVerificationEnabled();

        // When - apply potentially bad configuration
        try {
            SSLContext newSslContext = SslUtils.createTrustAllSslContext();
            client.setSslContext(newSslContext);
            client.setHostnameVerificationEnabled(false);
            client.setAuthenticationFilter(null);

            // Verify bad configuration is applied
            assertNotSame(savedSslContext, client.getSslContext(), "SSL context should be updated");
            assertNull(client.getAuthenticationFilter(), "Auth filter should be removed");
            assertFalse(client.isHostnameVerificationEnabled(), "Hostname verification should be disabled");

        } catch (Exception e) {
            // If configuration fails, rollback
            client.setSslContext(savedSslContext);
            client.setAuthenticationFilter(savedAuthFilter);
            client.setHostnameVerificationEnabled(savedHostnameVerification);
        }

        // When - rollback to good configuration
        client.setSslContext(savedSslContext);
        client.setAuthenticationFilter(savedAuthFilter);
        client.setHostnameVerificationEnabled(savedHostnameVerification);

        // Then - good configuration should be restored
        assertNotNull(client.getSslContext(), "SSL context should be restored");
        assertNotNull(client.getAuthenticationFilter(), "Auth filter should be restored");
        assertTrue(client.isHostnameVerificationEnabled(), "Hostname verification should be restored");
    }

    // Helper method to apply test configuration updates
    private void applyTestConfigurationUpdate(NarayanaLRAClient client, String configVersion) throws Exception {
        switch (configVersion) {
            case "initial":
                // Initial configuration: basic SSL
                client.setSslContext(SslUtils.createDefaultSslContext());
                break;

            case "updated":
                // Updated configuration: add JWT authentication
                System.setProperty("lra.coordinator.jwt.enabled", "true");
                System.setProperty("lra.coordinator.jwt.token", "updated-token");
                ClientRequestFilter jwtFilter = JwtAuthenticationUtils.createJwtFilterIfEnabled();
                client.setAuthenticationFilter(jwtFilter);
                break;

            case "final":
                // Final configuration: enhanced SSL + updated JWT
                SSLContext enhancedSslContext = SslUtils.createTrustAllSslContext();
                client.setSslContext(enhancedSslContext);

                ClientRequestFilter finalJwtFilter = JwtAuthenticationUtils.createJwtFilter("final-token");
                client.setAuthenticationFilter(finalJwtFilter);
                break;
        }
    }

    private void clearSystemProperties() {
        System.clearProperty("app.environment");
        System.clearProperty("security.level");
        System.clearProperty("lra.coordinator.jwt.enabled");
        System.clearProperty("lra.coordinator.jwt.token");
        System.clearProperty("lra.coordinator.ssl.truststore.path");
        System.clearProperty("lra.coordinator.ssl.truststore.password");
        System.clearProperty("lra.coordinator.ssl.truststore.type");
        System.clearProperty("lra.coordinator.ssl.keystore.path");
        System.clearProperty("lra.coordinator.ssl.keystore.password");
        System.clearProperty("lra.coordinator.ssl.keystore.type");
        System.clearProperty("lra.coordinator.ssl.verify-hostname");
    }
}
