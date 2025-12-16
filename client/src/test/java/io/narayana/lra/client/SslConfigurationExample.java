/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client;

import io.narayana.lra.client.internal.NarayanaLRAClient;
import io.narayana.lra.client.internal.SslUtils;
import javax.net.ssl.SSLContext;

/**
 * Examples demonstrating SSL configuration for NarayanaLRAClient.
 */
public class SslConfigurationExample {

    /**
     * Example 1: Configuration-based SSL with truststore.
     * Set these properties in your microprofile-config.properties:
     */
    public static void configBasedSslExample() {
        /*
         * Configuration properties:
         * lra.coordinator.ssl.truststore.path=/path/to/truststore.jks
         * lra.coordinator.ssl.truststore.password=truststore-password
         * lra.coordinator.ssl.truststore.type=JKS
         * lra.coordinator.ssl.verify-hostname=true
         */

        // Create client - SSL will be configured automatically from properties
        NarayanaLRAClient client = new NarayanaLRAClient("https://secure-coordinator.example.com/lra-coordinator");

        // SSL configuration is applied automatically from microprofile-config.properties
        // Use the client as normal...
    }

    /**
     * Example 2: Programmatic SSL configuration with custom truststore.
     */
    public static void programmaticSslExample() {
        try {
            // Create SSL context with custom truststore
            SSLContext sslContext = SslUtils.createSslContextWithTruststore(
                    "/path/to/custom-truststore.jks",
                    "truststore-password",
                    "JKS");

            // Create client and configure SSL
            NarayanaLRAClient client = new NarayanaLRAClient("https://secure-coordinator.example.com/lra-coordinator");
            client.setSslContext(sslContext);

            // Optional: Disable hostname verification (not recommended for production)
            client.setHostnameVerificationEnabled(false);

            // Use the client...
        } catch (Exception e) {
            throw new RuntimeException("Failed to configure SSL", e);
        }
    }

    /**
     * Example 3: Mutual TLS (client certificate authentication).
     */
    public static void mutualTlsExample() {
        try {
            // Create SSL context with both truststore and keystore
            SSLContext sslContext = SslUtils.createMutualTlsSslContext(
                    "/path/to/truststore.jks", "truststore-password", "JKS",
                    "/path/to/client-keystore.jks", "keystore-password", "JKS");

            NarayanaLRAClient client = new NarayanaLRAClient("https://mtls-coordinator.example.com/lra-coordinator");
            client.setSslContext(sslContext);

            // Use the client - requests will include client certificate
        } catch (Exception e) {
            throw new RuntimeException("Failed to configure mutual TLS", e);
        }
    }

    /**
     * Example 4: Development/testing with trust-all SSL (INSECURE).
     */
    public static void trustAllSslExample() {
        try {
            // WARNING: This trusts all certificates - only for development!
            SSLContext trustAllContext = SslUtils.createTrustAllSslContext();

            NarayanaLRAClient client = new NarayanaLRAClient("https://dev-coordinator.example.com/lra-coordinator");
            client.setSslContext(trustAllContext);
            client.setHostnameVerificationEnabled(false);

            // Use the client for development/testing...
        } catch (Exception e) {
            throw new RuntimeException("Failed to configure trust-all SSL", e);
        }
    }

    /**
     * Example 5: Combined JWT authentication and SSL configuration.
     */
    public static void combinedJwtAndSslExample() {
        try {
            // Configure SSL
            SSLContext sslContext = SslUtils.createSslContextWithTruststore(
                    "/path/to/truststore.jks",
                    "password",
                    "JKS");

            // Create client
            NarayanaLRAClient client = new NarayanaLRAClient("https://secure-coordinator.example.com/lra-coordinator");

            // Configure SSL
            client.setSslContext(sslContext);

            // Configure JWT authentication
            System.setProperty("lra.coordinator.jwt.enabled", "true");
            System.setProperty("lra.coordinator.jwt.token", "eyJhbGciOiJSUzI1NiJ9...");

            // Auto-configure JWT filter from properties
            var jwtFilter = io.narayana.lra.client.internal.JwtAuthenticationUtils.createJwtFilterIfEnabled();
            client.setAuthenticationFilter(jwtFilter);

            // Client now has both SSL and JWT authentication configured
        } catch (Exception e) {
            throw new RuntimeException("Failed to configure secure client", e);
        }
    }

    /**
     * Example configuration properties for microprofile-config.properties:
     */
    /*
     * # SSL Configuration
     * lra.coordinator.ssl.truststore.path=/opt/app/security/truststore.jks
     * lra.coordinator.ssl.truststore.password=changeit
     * lra.coordinator.ssl.truststore.type=JKS
     * lra.coordinator.ssl.verify-hostname=true
     *
     * # For mutual TLS, also add:
     * lra.coordinator.ssl.keystore.path=/opt/app/security/client-keystore.jks
     * lra.coordinator.ssl.keystore.password=changeit
     * lra.coordinator.ssl.keystore.type=JKS
     *
     * # JWT Authentication (optional)
     * lra.coordinator.jwt.enabled=true
     * lra.coordinator.jwt.token=eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...
     *
     * # Combined secure coordinator URL
     * lra.coordinator.url=https://secure-coordinator.example.com/lra-coordinator
     */
}
