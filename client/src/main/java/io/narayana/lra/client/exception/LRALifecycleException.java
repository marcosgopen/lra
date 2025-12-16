/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client.exception;

import jakarta.ws.rs.core.Response;
import java.net.URI;

/**
 * Exception thrown when LRA lifecycle operations (start, end, status) fail.
 */
public class LRALifecycleException extends LRAClientException {

    private static final String ERROR_CODE = "LRA_LIFECYCLE_ERROR";

    private final URI lraId;
    private final String operation;

    public LRALifecycleException(String operation, String message, URI lraId) {
        super(ERROR_CODE, message, lraId, operation);
        this.lraId = lraId;
        this.operation = operation;
    }

    public LRALifecycleException(String operation, String message, URI lraId, Throwable cause) {
        super(ERROR_CODE, message, cause, lraId, operation);
        this.lraId = lraId;
        this.operation = operation;
    }

    public LRALifecycleException(String operation, String message, URI lraId, Response response) {
        super(ERROR_CODE, message, response, lraId, operation);
        this.lraId = lraId;
        this.operation = operation;
    }

    public URI getLraId() {
        return lraId;
    }

    public String getOperation() {
        return operation;
    }

    /**
     * Create exception for LRA start failures.
     */
    public static LRALifecycleException startFailed(String message, Throwable cause) {
        return new LRALifecycleException("start", "Failed to start LRA: " + message, null, cause);
    }

    /**
     * Create exception for LRA end failures.
     */
    public static LRALifecycleException endFailed(URI lraId, boolean close, String message, Throwable cause) {
        String operation = close ? "close" : "cancel";
        String fullMessage = String.format("Failed to %s LRA %s: %s", operation, lraId, message);
        return new LRALifecycleException(operation, fullMessage, lraId, cause);
    }

    /**
     * Create exception for LRA status query failures.
     */
    public static LRALifecycleException statusQueryFailed(URI lraId, String message, Throwable cause) {
        String fullMessage = String.format("Failed to query status of LRA %s: %s", lraId, message);
        return new LRALifecycleException("status", fullMessage, lraId, cause);
    }

    /**
     * Create exception for invalid LRA timeouts.
     */
    public static LRALifecycleException invalidTimeout(long timeout) {
        String message = String.format("Invalid LRA timeout: %d (must be >= 0)", timeout);
        return new LRALifecycleException("start", message, null,
                Response.status(Response.Status.BAD_REQUEST).entity(message).build());
    }

    /**
     * Create exception for unexpected HTTP response status.
     */
    public static LRALifecycleException unexpectedResponseStatus(String operation, URI lraId, int status, String body) {
        String message = String.format("Unexpected response status %d for %s operation on LRA %s: %s",
                status, operation, lraId, body);
        return new LRALifecycleException(operation, message, lraId,
                Response.status(status).entity(body).build());
    }
}