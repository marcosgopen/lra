/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */
package io.narayana.lra.client.example;

import io.narayana.lra.client.builder.LRAClientBuilder;
import io.narayana.lra.client.config.LRAClientConfig;
import io.narayana.lra.client.exception.LRAClientException;
import io.narayana.lra.client.exception.LRAConfigurationException;
import io.narayana.lra.client.internal.JwtAuthenticationUtils;
import io.narayana.lra.client.internal.NarayanaLRAClient;
import io.narayana.lra.client.internal.SslUtils;
import jakarta.ws.rs.client.ClientRequestFilter;
import java.net.URI;
import java.time.temporal.ChronoUnit;
import javax.net.ssl.SSLContext;

/**
 * Comprehensive example demonstrating how to use the refactored LRA client
 * architecture. Shows best practices for configuration, creation, and usage of
 * the LRA client.
 */
public class RefactoredLRAClientExample {

    public static void main(String[] args) {
        // Demonstrate different ways to create and configure LRA clients
        try {
            // Example 1: Simple client creation with builder
            simpleClientExample();
            // Example 2: Configuration-driven client creation
            configurationDrivenExample();
            // Example 3: Programmatic configuration with fluent API
            programmaticConfigurationExample();
            // Example 4: Enterprise configuration with full security
            enterpriseConfigurationExample();
            // Example 5: Error handling and exception management
            errorHandlingExample();
        } catch (Exception e) {
            System.err.println("Example execution failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Example 1: Simple client creation with minimal configuration.
     */
    private static void simpleClientExample() throws Exception {
        System.out.println("=== Simple Client Example ===");
        System.setProperty(NarayanaLRAClient.LRA_COORDINATOR_URL_KEY, "http://localhost:8080/lra-coordinator");
        // Create a basic client using the builder pattern
        NarayanaLRAClient client = LRAClientBuilder.newBuilder()
                .build();
        // Use the client for basic LRA operations
        URI lraId = client.startLRA("simple-example");
        System.out.println("Started LRA: " + lraId);
        // Clean up
        client.closeLRA(lraId);
        client.close();
        System.out.println("Simple example completed successfully\n");
    }

    /**
     * Example 2: Configuration-driven client creation using MicroProfile Config.
     */
    private static void configurationDrivenExample() throws Exception {
        System.out.println("=== Configuration-Driven Example ===");
        // Create client config instance (this would typically be injected in a real
        // application)
        // For this example, we'll show how it would work programmatically
        LRAClientConfig clientConfig = createExampleConfig();
        // Create client from configuration
        NarayanaLRAClient client = LRAClientBuilder.newBuilder(clientConfig).build();
        // Demonstrate client usage
        URI lraId = client.startLRA(null, "config-driven-example", 300L, ChronoUnit.SECONDS);
        System.out.println("Started LRA with timeout: " + lraId);
        // Clean up
        client.cancelLRA(lraId);
        client.close();
        System.out.println("Configuration-driven example completed successfully\n");
    }

    /**
     * Example 3: Programmatic configuration using fluent API.
     */
    private static void programmaticConfigurationExample() throws Exception {
        System.out.println("=== Programmatic Configuration Example ===");
        // Create JWT authentication filter
        ClientRequestFilter jwtFilter = JwtAuthenticationUtils.createJwtFilter("example-jwt-token");
        // Create SSL context for secure communication
        SSLContext sslContext = SslUtils.createDefaultSslContext();
        System.setProperty(NarayanaLRAClient.LRA_COORDINATOR_URL_KEY, "http://localhost:8080/lra-coordinator");
        // Build client with programmatic configuration
        NarayanaLRAClient client = LRAClientBuilder.newBuilder()
                .withAuthentication(jwtFilter).withSslContext(sslContext).hostnameVerification(true).build();
        // Demonstrate advanced LRA operations
        URI parentLRA = client.startLRA("parent-lra");
        URI childLRA = client.startLRA(parentLRA, "child-lra", 600L, ChronoUnit.SECONDS);
        System.out.println("Started parent LRA: " + parentLRA);
        System.out.println("Started child LRA: " + childLRA);
        // Clean up in reverse order
        client.closeLRA(childLRA);
        client.closeLRA(parentLRA);
        client.close();
        System.out.println("Programmatic configuration example completed successfully\n");
    }

    /**
     * Example 4: Enterprise configuration with comprehensive security.
     */
    private static void enterpriseConfigurationExample() throws Exception {
        System.out.println("=== Enterprise Configuration Example ===");
        // Create mutual TLS SSL context (in real scenario, paths would be valid)
        SSLContext mutualTlsContext;
        try {
            mutualTlsContext = SslUtils.createMutualTlsSslContext("/etc/ssl/certs/truststore.jks",
                    "truststore-password", "JKS", "/etc/ssl/certs/keystore.jks", "keystore-password", "JKS");
        } catch (Exception e) {
            // Fall back to default SSL context for this example
            mutualTlsContext = SslUtils.createDefaultSslContext();
            System.out.println("Using default SSL context (mutual TLS files not available)");
        }
        System.setProperty(NarayanaLRAClient.LRA_COORDINATOR_URL_KEY,
                "http://localhost:8080/lra-coordinator,http://localhost:8082/lra-coordinator");
        // Create enterprise-grade client configuration
        NarayanaLRAClient client = LRAClientBuilder.newBuilder()
                .withSslContext(mutualTlsContext)
                .authentication().jwt("production-jwt-token")
                .ssl().hostnameVerification(true)
                .build();
        // Demonstrate production-like usage
        URI lraId = client.startLRA(null, "enterprise-transaction", 1800L, ChronoUnit.SECONDS);
        System.out.println("Started enterprise LRA: " + lraId);
        // Simulate some business operations
        simulateBusinessOperations(client, lraId);
        // Complete the LRA
        client.closeLRA(lraId);
        client.close();
        System.out.println("Enterprise configuration example completed successfully\n");
    }

    /**
     * Example 5: Error handling and exception management.
     */
    private static void errorHandlingExample() {
        System.out.println("=== Error Handling Example ===");
        try {
            // Attempt to create client with invalid configuration
            NarayanaLRAClient client = LRAClientBuilder.newBuilder().coordinatorUrl("invalid://malformed-url").build();
            // This should not be reached
            client.close();
        } catch (LRAConfigurationException e) {
            System.out.println("Caught configuration exception: " + e.getMessage());
            System.out.println("Error code: " + e.getErrorCode());
        } catch (LRAClientException e) {
            System.out.println("Caught LRA client exception: " + e.getMessage());
            if (e.hasErrorCode()) {
                System.out.println("Error code: " + e.getErrorCode());
            }
        } catch (Exception e) {
            System.out.println("Caught unexpected exception: " + e.getMessage());
        }
        System.out.println("Error handling example completed\n");
    }

    /**
     * Simulate some business operations with the LRA.
     */
    private static void simulateBusinessOperations(NarayanaLRAClient client, URI lraId) throws Exception {
        // In a real application, these would be actual business service calls
        System.out.println("Simulating business operations for LRA: " + lraId);
        // Check LRA status
        var status = client.getStatus(lraId);
        System.out.println("LRA Status: " + status);
        // Simulate some processing time
        Thread.sleep(100);
        System.out.println("Business operations completed successfully");
    }

    /**
     * Create example configuration for demonstration. In a real application, this
     * would be provided by the MicroProfile Config implementation.
     */
    private static LRAClientConfig createExampleConfig() {
        // This is a simplified example - in practice, this would be implemented
        // by the MicroProfile Config provider based on configuration files/environment
        return new LRAClientConfig() {

            @Override
            public java.util.Optional<String> url() {
                return java.util.Optional.of("http://localhost:8080/lra-coordinator");
            }

            @Override
            public io.narayana.lra.client.config.SslConfiguration ssl() {
                return new io.narayana.lra.client.config.SslConfiguration() {

                    @Override
                    public java.util.Optional<String> truststorePath() {
                        return java.util.Optional.empty();
                    }

                    @Override
                    public java.util.Optional<String> truststorePassword() {
                        return java.util.Optional.empty();
                    }

                    @Override
                    public java.util.Optional<String> truststoreType() {
                        return java.util.Optional.empty();
                    }

                    @Override
                    public java.util.Optional<String> keystorePath() {
                        return java.util.Optional.empty();
                    }

                    @Override
                    public java.util.Optional<String> keystorePassword() {
                        return java.util.Optional.empty();
                    }

                    @Override
                    public java.util.Optional<String> keystoreType() {
                        return java.util.Optional.empty();
                    }

                    @Override
                    public java.util.Optional<Boolean> verifyHostname() {
                        return java.util.Optional.of(true);
                    }

                    @Override
                    public java.util.Optional<String> context() {
                        return java.util.Optional.empty();
                    }
                };
            }

            @Override
            public io.narayana.lra.client.config.JwtConfiguration jwt() {
                return new io.narayana.lra.client.config.JwtConfiguration() {

                    @Override
                    public java.util.Optional<Boolean> enabled() {
                        return java.util.Optional.of(false);
                    }

                    @Override
                    public java.util.Optional<String> token() {
                        return java.util.Optional.empty();
                    }

                    @Override
                    public java.util.Optional<String> header() {
                        return java.util.Optional.empty();
                    }

                    @Override
                    public java.util.Optional<String> prefix() {
                        return java.util.Optional.empty();
                    }
                };
            }

            @Override
            public io.narayana.lra.client.config.LoadBalancerConfiguration loadBalancer() {
                return new io.narayana.lra.client.config.LoadBalancerConfiguration() {

                    @Override
                    public java.util.Optional<String> lbMethod() {
                        return java.util.Optional.of("round-robin");
                    }
                };
            }
        };
    }
}
