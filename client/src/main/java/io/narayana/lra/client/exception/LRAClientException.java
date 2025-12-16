/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client.exception;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/**
 * Base exception for all LRA client-related errors.
 * Extends WebApplicationException to maintain compatibility with existing JAX-RS error handling.
 */
public class LRAClientException extends WebApplicationException {

    private final String errorCode;
    private final Object[] messageParameters;

    public LRAClientException(String message) {
        super(message);
        this.errorCode = null;
        this.messageParameters = null;
    }

    public LRAClientException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = null;
        this.messageParameters = null;
    }

    public LRAClientException(String message, Response response) {
        super(message, response);
        this.errorCode = null;
        this.messageParameters = null;
    }

    public LRAClientException(String message, Throwable cause, Response response) {
        super(message, cause, response);
        this.errorCode = null;
        this.messageParameters = null;
    }

    /**
     * Constructor with error code for internationalization support.
     */
    public LRAClientException(String errorCode, String message, Object... messageParameters) {
        super(message);
        this.errorCode = errorCode;
        this.messageParameters = messageParameters;
    }

    /**
     * Constructor with error code and cause.
     */
    public LRAClientException(String errorCode, String message, Throwable cause, Object... messageParameters) {
        super(message, cause);
        this.errorCode = errorCode;
        this.messageParameters = messageParameters;
    }

    /**
     * Constructor with error code and HTTP response.
     */
    public LRAClientException(String errorCode, String message, Response response, Object... messageParameters) {
        super(message, response);
        this.errorCode = errorCode;
        this.messageParameters = messageParameters;
    }

    /**
     * Get the error code for this exception (used for internationalization).
     */
    public String getErrorCode() {
        return errorCode;
    }

    /**
     * Get the message parameters for this exception.
     */
    public Object[] getMessageParameters() {
        return messageParameters;
    }

    /**
     * Check if this exception has an error code.
     */
    public boolean hasErrorCode() {
        return errorCode != null;
    }
}