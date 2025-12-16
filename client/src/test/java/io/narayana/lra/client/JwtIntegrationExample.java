/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client.example;

import io.narayana.lra.client.internal.JwtAuthenticationUtils;
import io.narayana.lra.client.internal.NarayanaLRAClient;
import jakarta.ws.rs.client.ClientRequestFilter;

/**
 * Example demonstrating how to configure JWT authentication with NarayanaLRAClient.
 * This is for documentation and testing purposes.
 */
public class JwtIntegrationExample {

    /**
     * Example 1: Using configuration-based JWT authentication.
     * Set these properties in your microprofile-config.properties or as system properties:
     * - lra.coordinator.jwt.enabled=true
     * - lra.coordinator.jwt.token=your-jwt-token-here
     */
    public static void configBasedExample() {
        // Create client
        NarayanaLRAClient client = new NarayanaLRAClient("https://coordinator.example.com/lra-coordinator");

        // Set up JWT authentication from configuration
        ClientRequestFilter jwtFilter = JwtAuthenticationUtils.createJwtFilterIfEnabled();
        client.setAuthenticationFilter(jwtFilter);

        // Now all requests to the coordinator will include JWT authentication if enabled in config
        // Use the client as normal...
    }

    /**
     * Example 2: Using explicit JWT token.
     * Use this when you want to programmatically set the JWT token.
     */
    public static void explicitTokenExample() {
        // Create client
        NarayanaLRAClient client = new NarayanaLRAClient("https://coordinator.example.com/lra-coordinator");

        // Set JWT token explicitly
        String jwtToken = obtainJwtTokenFromSomewhere();
        ClientRequestFilter jwtFilter = JwtAuthenticationUtils.createJwtFilter(jwtToken);
        client.setAuthenticationFilter(jwtFilter);

        // Now all requests will include the specified JWT token
        // Use the client as normal...
    }

    /**
     * Example 3: Using injected JWT in CDI context.
     * When running in a CDI environment with MicroProfile JWT, the filter will automatically
     * pick up the current JWT context.
     */
    public static void cdiInjectedExample() {
        // Create client
        NarayanaLRAClient client = new NarayanaLRAClient("https://coordinator.example.com/lra-coordinator");

        // Enable JWT (can also be done via configuration)
        System.setProperty("lra.coordinator.jwt.enabled", "true");
        ClientRequestFilter jwtFilter = JwtAuthenticationUtils.createJwtFilterIfEnabled();
        client.setAuthenticationFilter(jwtFilter);

        // The filter will automatically use the current JWT context when available
        // Use the client as normal...
    }

    /**
     * Example 4: Conditional JWT authentication.
     * Only enable JWT for certain coordinators or environments.
     */
    public static void conditionalExample() {
        String coordinatorUrl = "https://coordinator.example.com/lra-coordinator";
        NarayanaLRAClient client = new NarayanaLRAClient(coordinatorUrl);

        // Only enable JWT for production coordinators
        if (coordinatorUrl.contains("production.example.com")) {
            ClientRequestFilter jwtFilter = JwtAuthenticationUtils.createJwtFilterIfEnabled();
            client.setAuthenticationFilter(jwtFilter);
        }

        // Use the client as normal...
    }

    /**
     * Example configuration properties for microprofile-config.properties:
     *
     * # Enable JWT authentication
     * lra.coordinator.jwt.enabled=true
     *
     * # JWT token (if not using CDI injection)
     * lra.coordinator.jwt.token=eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...
     *
     * # Optional: Custom header name (default: Authorization)
     * lra.coordinator.jwt.header=Authorization
     *
     * # Optional: Custom token prefix (default: "Bearer ")
     * lra.coordinator.jwt.prefix=Bearer
     */

    private static String obtainJwtTokenFromSomewhere() {
        // This would typically come from your security infrastructure
        // For example: OIDC provider, service registry, etc.
        return "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.example.token";
    }
}