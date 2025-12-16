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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration test that verifies the complete security configuration
 * functionality works together correctly.
 */
public class SecurityIntegrationTest {

    @BeforeEach
    public void setUp() {
        clearSystemProperties();
    }

    @AfterEach
    public void tearDown() {
        clearSystemProperties();
    }

    @Test
    @DisplayName("Complete security configuration workflow")
    public void testCompleteSecurityWorkflow() throws Exception {
        // Given - a new LRA client
        NarayanaLRAClient client = new NarayanaLRAClient("https://secure-coordinator.example.com/lra-coordinator");

        // Initially no security configured
        assertNull(client.getAuthenticationFilter(), "Client should start without authentication");
        assertNull(client.getSslContext(), "Client should start without custom SSL");
        assertTrue(client.isHostnameVerificationEnabled(), "Hostname verification should be enabled by default");

        // Step 1: Add JWT authentication
        System.setProperty("lra.coordinator.jwt.enabled", "true");
        System.setProperty("lra.coordinator.jwt.token", "secure-jwt-token");

        ClientRequestFilter jwtFilter = JwtAuthenticationUtils.createJwtFilterIfEnabled();
        client.setAuthenticationFilter(jwtFilter);

        assertNotNull(client.getAuthenticationFilter(), "JWT authentication should be configured");

        // Step 2: Add SSL configuration
        SSLContext sslContext = SslUtils.createDefaultSslContext();
        client.setSslContext(sslContext);

        assertNotNull(client.getSslContext(), "SSL context should be configured");
        assertSame(sslContext, client.getSslContext(), "SSL context should be the one we set");

        // Step 3: Configure hostname verification
        client.setHostnameVerificationEnabled(false);
        assertFalse(client.isHostnameVerificationEnabled(), "Hostname verification should be disabled");

        // Step 4: Update authentication token
        String newToken = "updated-jwt-token";
        ClientRequestFilter newJwtFilter = JwtAuthenticationUtils.createJwtFilter(newToken);
        client.setAuthenticationFilter(newJwtFilter);

        assertNotSame(jwtFilter, client.getAuthenticationFilter(), "Authentication filter should be updated");
        assertNotNull(client.getAuthenticationFilter(), "New authentication filter should be set");

        // Step 5: Update SSL context
        SSLContext trustAllContext = SslUtils.createTrustAllSslContext();
        client.setSslContext(trustAllContext);

        assertNotSame(sslContext, client.getSslContext(), "SSL context should be updated");
        assertSame(trustAllContext, client.getSslContext(), "New SSL context should be set");

        // Step 6: Manual reinitialization
        client.reinitializeRestClient();

        // All configuration should persist
        assertNotNull(client.getAuthenticationFilter(), "Authentication should persist after reinitialization");
        assertNotNull(client.getSslContext(), "SSL context should persist after reinitialization");
        assertFalse(client.isHostnameVerificationEnabled(), "Hostname verification should persist after reinitialization");

        // Step 7: Remove security configuration
        client.setAuthenticationFilter(null);
        client.setSslContext(null);
        client.setHostnameVerificationEnabled(true);

        assertNull(client.getAuthenticationFilter(), "Authentication should be removed");
        assertNull(client.getSslContext(), "SSL context should be removed");
        assertTrue(client.isHostnameVerificationEnabled(), "Hostname verification should be re-enabled");
    }

    @Test
    @DisplayName("Environment-based configuration scenario")
    public void testEnvironmentBasedConfiguration() throws Exception {
        // Simulate different environments
        String[] environments = { "development", "staging", "production" };

        for (String environment : environments) {
            System.setProperty("app.environment", environment);

            NarayanaLRAClient client = new NarayanaLRAClient("https://" + environment + ".example.com/lra-coordinator");

            // Configure based on environment
            switch (environment) {
                case "development":
                    // Development: relaxed security
                    SSLContext trustAllContext = SslUtils.createTrustAllSslContext();
                    client.setSslContext(trustAllContext);
                    client.setHostnameVerificationEnabled(false);
                    // No JWT for development
                    break;

                case "staging":
                    // Staging: moderate security
                    SSLContext stagingContext = SslUtils.createDefaultSslContext();
                    client.setSslContext(stagingContext);
                    client.setHostnameVerificationEnabled(true);

                    // Add JWT for staging
                    ClientRequestFilter stagingJwt = JwtAuthenticationUtils.createJwtFilter("staging-token");
                    client.setAuthenticationFilter(stagingJwt);
                    break;

                case "production":
                    // Production: full security
                    SSLContext productionContext = SslUtils.createDefaultSslContext();
                    client.setSslContext(productionContext);
                    client.setHostnameVerificationEnabled(true);

                    // Add JWT for production
                    ClientRequestFilter productionJwt = JwtAuthenticationUtils.createJwtFilter("production-token");
                    client.setAuthenticationFilter(productionJwt);
                    break;
            }

            // Verify configuration is appropriate for environment
            assertNotNull(client.getSslContext(), "SSL context should be configured for " + environment);

            switch (environment) {
                case "development":
                    assertNull(client.getAuthenticationFilter(), "Development should not have JWT");
                    assertFalse(client.isHostnameVerificationEnabled(), "Development should not verify hostnames");
                    break;
                case "staging":
                case "production":
                    assertNotNull(client.getAuthenticationFilter(), environment + " should have JWT");
                    assertTrue(client.isHostnameVerificationEnabled(), environment + " should verify hostnames");
                    break;
            }
        }
    }

    @Test
    @DisplayName("Configuration error handling")
    public void testConfigurationErrorHandling() throws Exception {
        NarayanaLRAClient client = new NarayanaLRAClient("https://coordinator.example.com/lra-coordinator");

        // Test that invalid SSL configuration doesn't break the client
        try {
            SslUtils.createSslContextWithTruststore("/nonexistent/file.jks", "password", "JKS");
            fail("Should have thrown exception for nonexistent truststore");
        } catch (Exception e) {
            // Expected - file doesn't exist
            assertTrue(
                    e instanceof java.io.FileNotFoundException ||
                            e.getCause() instanceof java.io.FileNotFoundException,
                    "Should be file-related exception");
        }

        // Client should still be functional with valid SSL configuration
        SSLContext validContext = SslUtils.createDefaultSslContext();
        client.setSslContext(validContext);

        assertNotNull(client.getSslContext(), "Valid SSL context should be set");

        // Test JWT configuration without system properties
        System.clearProperty("lra.coordinator.jwt.enabled");
        ClientRequestFilter jwtFilter = JwtAuthenticationUtils.createJwtFilterIfEnabled();

        assertNull(jwtFilter, "JWT filter should not be created when disabled");

        // Client should still work with explicit JWT
        ClientRequestFilter explicitJwt = JwtAuthenticationUtils.createJwtFilter("explicit-token");
        client.setAuthenticationFilter(explicitJwt);

        assertNotNull(client.getAuthenticationFilter(), "Explicit JWT should be set");
    }

    @Test
    @DisplayName("Multiple client instances independence")
    public void testMultipleClientInstances() throws Exception {
        // Create multiple clients
        NarayanaLRAClient client1 = new NarayanaLRAClient("https://coordinator1.example.com/lra-coordinator");
        NarayanaLRAClient client2 = new NarayanaLRAClient("https://coordinator2.example.com/lra-coordinator");
        NarayanaLRAClient client3 = new NarayanaLRAClient("https://coordinator3.example.com/lra-coordinator");

        // Configure each differently
        // Client 1: JWT only
        ClientRequestFilter jwt1 = JwtAuthenticationUtils.createJwtFilter("token1");
        client1.setAuthenticationFilter(jwt1);

        // Client 2: SSL only
        SSLContext ssl2 = SslUtils.createDefaultSslContext();
        client2.setSslContext(ssl2);
        client2.setHostnameVerificationEnabled(false);

        // Client 3: Both JWT and SSL
        ClientRequestFilter jwt3 = JwtAuthenticationUtils.createJwtFilter("token3");
        SSLContext ssl3 = SslUtils.createTrustAllSslContext();
        client3.setAuthenticationFilter(jwt3);
        client3.setSslContext(ssl3);
        client3.setHostnameVerificationEnabled(false);

        // Verify independence
        // Client 1
        assertSame(jwt1, client1.getAuthenticationFilter(), "Client1 should have JWT");
        assertNull(client1.getSslContext(), "Client1 should not have custom SSL");
        assertTrue(client1.isHostnameVerificationEnabled(), "Client1 should have default hostname verification");

        // Client 2
        assertNull(client2.getAuthenticationFilter(), "Client2 should not have JWT");
        assertSame(ssl2, client2.getSslContext(), "Client2 should have SSL");
        assertFalse(client2.isHostnameVerificationEnabled(), "Client2 should have disabled hostname verification");

        // Client 3
        assertSame(jwt3, client3.getAuthenticationFilter(), "Client3 should have JWT");
        assertSame(ssl3, client3.getSslContext(), "Client3 should have SSL");
        assertFalse(client3.isHostnameVerificationEnabled(), "Client3 should have disabled hostname verification");

        // Update one client and verify others are unaffected
        client1.setHostnameVerificationEnabled(false);

        assertFalse(client1.isHostnameVerificationEnabled(), "Client1 hostname verification should be updated");
        assertFalse(client2.isHostnameVerificationEnabled(), "Client2 hostname verification should be unchanged");
        assertFalse(client3.isHostnameVerificationEnabled(), "Client3 hostname verification should be unchanged");
    }

    private void clearSystemProperties() {
        System.clearProperty("app.environment");
        System.clearProperty("lra.coordinator.jwt.enabled");
        System.clearProperty("lra.coordinator.jwt.token");
        System.clearProperty("lra.coordinator.ssl.truststore.path");
        System.clearProperty("lra.coordinator.ssl.truststore.password");
        System.clearProperty("lra.coordinator.ssl.verify-hostname");
    }
}
