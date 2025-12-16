/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client.exception;

import jakarta.ws.rs.core.Response;
import java.net.URI;

/**
 * Exception thrown when participant enrollment or management operations fail.
 */
public class LRAParticipantException extends LRAClientException {

    private static final String ERROR_CODE = "LRA_PARTICIPANT_ERROR";

    private final URI lraId;

    public LRAParticipantException(String message, URI lraId) {
        super(ERROR_CODE, message, lraId);
        this.lraId = lraId;
    }

    public LRAParticipantException(String message, URI lraId, Throwable cause) {
        super(ERROR_CODE, message, cause, lraId);
        this.lraId = lraId;
    }

    public LRAParticipantException(String message, URI lraId, Response response) {
        super(ERROR_CODE, message, response, lraId);
        this.lraId = lraId;
    }

    public URI getLraId() {
        return lraId;
    }

    /**
     * Create exception for when it's too late to join an LRA.
     */
    public static LRAParticipantException tooLateToJoin(URI lraId, String details) {
        String message = String.format("Too late to join LRA %s: %s", lraId, details);
        return new LRAParticipantException(message, lraId,
                Response.status(Response.Status.PRECONDITION_FAILED).entity(message).build());
    }

    /**
     * Create exception for when an LRA is not found.
     */
    public static LRAParticipantException lraNotFound(URI lraId) {
        String message = String.format("LRA not found: %s", lraId);
        return new LRAParticipantException(message, lraId,
                Response.status(Response.Status.GONE).entity(message).build());
    }

    /**
     * Create exception for invalid participant URIs.
     */
    public static LRAParticipantException invalidParticipantUri(URI participantUri, Throwable cause) {
        String message = String.format("Invalid participant URI: %s", participantUri);
        return new LRAParticipantException(message, null,
                Response.status(Response.Status.NOT_ACCEPTABLE).entity(message).build());
    }

    /**
     * Create exception for invalid recovery URI returned by coordinator.
     */
    public static LRAParticipantException invalidRecoveryUri(URI lraId, String recoveryUri, String details) {
        String message = String.format("Invalid recovery URI '%s' for LRA %s: %s", recoveryUri, lraId, details);
        return new LRAParticipantException(message, lraId,
                Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(message).build());
    }
}