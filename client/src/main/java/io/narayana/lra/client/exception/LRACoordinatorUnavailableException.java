/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client.exception;

import jakarta.ws.rs.core.Response;

/**
 * Exception thrown when the LRA coordinator is unavailable or unreachable.
 */
public class LRACoordinatorUnavailableException extends LRAClientException {

    private static final String ERROR_CODE = "LRA_COORDINATOR_UNAVAILABLE";

    public LRACoordinatorUnavailableException(String message) {
        super(ERROR_CODE, message);
    }

    public LRACoordinatorUnavailableException(String message, Throwable cause) {
        super(ERROR_CODE, message, cause);
    }

    public LRACoordinatorUnavailableException(String message, String coordinatorUrl) {
        super(ERROR_CODE, message,
                Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(message).build(),
                coordinatorUrl);
    }

    public LRACoordinatorUnavailableException(String message, Throwable cause, String coordinatorUrl) {
        super(ERROR_CODE, message, cause, coordinatorUrl);
    }
}