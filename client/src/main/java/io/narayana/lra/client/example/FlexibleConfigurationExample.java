/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client.example;

import io.narayana.lra.client.internal.JwtAuthenticationUtils;
import io.narayana.lra.client.internal.NarayanaLRAClient;
import io.narayana.lra.client.internal.SslUtils;
import java.net.URI;
import javax.net.ssl.SSLContext;

/**
 * Examples demonstrating the flexible configuration capabilities of NarayanaLRAClient
 * with the dedicated REST client initialization method.
 */
public class FlexibleConfigurationExample {

    /**
     * Example 1: Dynamic authentication updates.
     * Shows how authentication can be updated without recreating the client.
     */
    public static void dynamicAuthenticationExample() {
        try {
            NarayanaLRAClient client = new NarayanaLRAClient("https://coordinator.example.com/lra-coordinator");

            // Start without authentication
            URI lra1 = client.startLRA("operation-without-auth");

            // Update to use JWT authentication - client automatically reinitializes
            String jwtToken = obtainJwtToken();
            var jwtFilter = JwtAuthenticationUtils.createJwtFilter(jwtToken);
            client.setAuthenticationFilter(jwtFilter);

            // Now all requests will include authentication
            URI lra2 = client.startLRA("operation-with-auth");

            // Later, update with a new token (e.g., after refresh)
            String refreshedToken = refreshJwtToken(jwtToken);
            var newJwtFilter = JwtAuthenticationUtils.createJwtFilter(refreshedToken);
            client.setAuthenticationFilter(newJwtFilter);

            URI lra3 = client.startLRA("operation-with-refreshed-auth");

            // Clean up
            client.cancelLRA(lra1);
            client.cancelLRA(lra2);
            client.cancelLRA(lra3);
        } catch (Exception e) {
            throw new RuntimeException("Dynamic authentication example failed", e);
        }
    }

    /**
     * Example 2: Environment-based SSL configuration.
     * Shows how SSL settings can be updated based on runtime conditions.
     */
    public static void environmentBasedSslExample() {
        try {
            NarayanaLRAClient client = new NarayanaLRAClient("https://coordinator.example.com/lra-coordinator");

            String environment = System.getProperty("app.environment", "development");

            switch (environment) {
                case "production":
                    // Production: Use proper certificates with hostname verification
                    SSLContext prodSslContext = SslUtils.createSslContextWithTruststore(
                            "/opt/security/prod-truststore.jks",
                            System.getenv("TRUSTSTORE_PASSWORD"),
                            "JKS");
                    client.setSslContext(prodSslContext);
                    client.setHostnameVerificationEnabled(true);
                    break;

                case "staging":
                    // Staging: Use staging certificates
                    SSLContext stagingSslContext = SslUtils.createSslContextWithTruststore(
                            "/opt/security/staging-truststore.jks",
                            "staging-password",
                            "JKS");
                    client.setSslContext(stagingSslContext);
                    client.setHostnameVerificationEnabled(true);
                    break;

                case "development":
                default:
                    // Development: Trust all certificates (INSECURE - development only)
                    SSLContext trustAllContext = SslUtils.createTrustAllSslContext();
                    client.setSslContext(trustAllContext);
                    client.setHostnameVerificationEnabled(false);
                    break;
            }

            // Client is now configured for the appropriate environment
            URI lra = client.startLRA("environment-specific-operation");
            client.cancelLRA(lra);

        } catch (Exception e) {
            throw new RuntimeException("Environment-based SSL configuration failed", e);
        }
    }

    /**
     * Example 3: Runtime security policy updates.
     * Shows how security settings can be updated based on runtime policies.
     */
    public static void runtimeSecurityPolicyExample() {
        try {
            NarayanaLRAClient client = new NarayanaLRAClient("https://coordinator.example.com/lra-coordinator");

            // Check if the current operation requires enhanced security
            boolean requiresEnhancedSecurity = determineSecurityRequirements();

            if (requiresEnhancedSecurity) {
                // Enable mutual TLS for high-security operations
                SSLContext mutualTlsContext = SslUtils.createMutualTlsSslContext(
                        "/opt/security/truststore.jks", "trust-password", "JKS",
                        "/opt/security/client-keystore.p12", "key-password", "PKCS12");
                client.setSslContext(mutualTlsContext);

                // Also enable JWT authentication
                var jwtFilter = JwtAuthenticationUtils.createJwtFilterIfEnabled();
                client.setAuthenticationFilter(jwtFilter);
            }

            // Perform the operation with appropriate security
            URI lra = client.startLRA("security-sensitive-operation");

            // Later, downgrade security for less sensitive operations
            if (requiresEnhancedSecurity) {
                // Switch back to basic SSL
                SSLContext basicSslContext = SslUtils.createDefaultSslContext();
                client.setSslContext(basicSslContext);
                client.setAuthenticationFilter(null); // Remove authentication
            }

            URI lra2 = client.startLRA("regular-operation");

            // Clean up
            client.cancelLRA(lra);
            client.cancelLRA(lra2);

        } catch (Exception e) {
            throw new RuntimeException("Runtime security policy example failed", e);
        }
    }

    /**
     * Example 4: Configuration hot-reload simulation.
     * Shows how configuration can be updated without stopping the application.
     */
    public static void configurationHotReloadExample() {
        try {
            NarayanaLRAClient client = new NarayanaLRAClient("https://coordinator.example.com/lra-coordinator");

            // Simulate configuration changes that might come from external sources
            // (config management systems, environment variables, etc.)

            // Initial configuration
            applyConfigurationUpdate(client, "initial");

            URI lra1 = client.startLRA("operation-with-initial-config");

            // Simulate configuration update (e.g., from config server)
            applyConfigurationUpdate(client, "updated");

            URI lra2 = client.startLRA("operation-with-updated-config");

            // Another configuration update
            applyConfigurationUpdate(client, "final");

            URI lra3 = client.startLRA("operation-with-final-config");

            // Clean up
            client.cancelLRA(lra1);
            client.cancelLRA(lra2);
            client.cancelLRA(lra3);

        } catch (Exception e) {
            throw new RuntimeException("Configuration hot-reload example failed", e);
        }
    }

    /**
     * Example 5: Explicit REST client reinitialization.
     * Shows when and how to manually reinitialize the REST client.
     */
    public static void explicitReinitializationExample() {
        try {
            NarayanaLRAClient client = new NarayanaLRAClient("https://coordinator.example.com/lra-coordinator");

            // Set multiple configuration properties at once to avoid multiple reinitializations
            var jwtFilter = JwtAuthenticationUtils.createJwtFilter("initial-token");
            SSLContext sslContext = SslUtils.createDefaultSslContext();

            // Temporarily disable automatic reinitialization by directly setting fields
            // (This is a conceptual example - actual implementation would need to support this)

            // Set all properties
            client.setAuthenticationFilter(jwtFilter); // This triggers reinitialization
            // Note: Each setter currently triggers reinitialization automatically

            // Alternative approach: Use a batch configuration method (if implemented)
            // client.updateConfiguration(builder -> builder
            //     .authenticationFilter(jwtFilter)
            //     .sslContext(sslContext)
            //     .hostnameVerification(true)
            // );

            // Or manually reinitialize if needed
            client.reinitializeRestClient();

            URI lra = client.startLRA("configured-operation");
            client.cancelLRA(lra);

        } catch (Exception e) {
            throw new RuntimeException("Explicit reinitialization example failed", e);
        }
    }

    // Helper methods

    private static String obtainJwtToken() {
        // Simulate obtaining JWT token from identity provider
        return "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.example.token";
    }

    private static String refreshJwtToken(String expiredToken) {
        // Simulate refreshing an expired JWT token
        return "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.refreshed.token";
    }

    private static boolean determineSecurityRequirements() {
        // Simulate runtime security policy evaluation
        return "HIGH".equals(System.getProperty("security.level", "NORMAL"));
    }

    private static void applyConfigurationUpdate(NarayanaLRAClient client, String configVersion) {
        try {
            switch (configVersion) {
                case "initial":
                    // Initial configuration: basic SSL
                    client.setSslContext(SslUtils.createDefaultSslContext());
                    break;

                case "updated":
                    // Updated configuration: add JWT authentication
                    var jwtFilter = JwtAuthenticationUtils.createJwtFilter("updated-token");
                    client.setAuthenticationFilter(jwtFilter);
                    break;

                case "final":
                    // Final configuration: enhanced SSL + updated JWT
                    SSLContext enhancedSslContext = SslUtils.createSslContextWithTruststore(
                            "/opt/security/enhanced-truststore.jks",
                            "enhanced-password",
                            "JKS");
                    client.setSslContext(enhancedSslContext);

                    var finalJwtFilter = JwtAuthenticationUtils.createJwtFilter("final-token");
                    client.setAuthenticationFilter(finalJwtFilter);
                    break;
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to apply configuration update: " + configVersion, e);
        }
    }
}
