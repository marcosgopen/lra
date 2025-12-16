/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client.exception;

import jakarta.ws.rs.core.Response;

/**
 * Exception thrown when there are configuration errors in the LRA client setup.
 */
public class LRAConfigurationException extends LRAClientException {

    private static final String ERROR_CODE = "LRA_CONFIGURATION_ERROR";

    public LRAConfigurationException(String message) {
        super(ERROR_CODE, message,
                Response.status(Response.Status.BAD_REQUEST).entity(message).build());
    }

    public LRAConfigurationException(String message, Throwable cause) {
        super(ERROR_CODE, message, cause);
    }

    public LRAConfigurationException(String message, String configProperty) {
        super(ERROR_CODE, message,
                Response.status(Response.Status.BAD_REQUEST).entity(message).build(),
                configProperty);
    }

    public LRAConfigurationException(String message, Throwable cause, String configProperty) {
        super(ERROR_CODE, message, cause, configProperty);
    }

    /**
     * Create configuration exception for invalid SSL configuration.
     */
    public static LRAConfigurationException invalidSslConfiguration(String message, Throwable cause) {
        return new LRAConfigurationException("Invalid SSL configuration: " + message, cause, "ssl");
    }

    /**
     * Create configuration exception for invalid JWT configuration.
     */
    public static LRAConfigurationException invalidJwtConfiguration(String message) {
        return new LRAConfigurationException("Invalid JWT configuration: " + message, "jwt");
    }

    /**
     * Create configuration exception for invalid load balancer configuration.
     */
    public static LRAConfigurationException invalidLoadBalancerConfiguration(String method) {
        return new LRAConfigurationException("Unsupported load balancer method: " + method, "load-balancer");
    }
}