/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian.ha;

import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.LRA_HTTP_CONTEXT_HEADER;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.lra.annotation.Compensate;
import org.eclipse.microprofile.lra.annotation.Complete;
import org.eclipse.microprofile.lra.annotation.Forget;
import org.eclipse.microprofile.lra.annotation.ParticipantStatus;
import org.eclipse.microprofile.lra.annotation.Status;

/**
 * A simple LRA participant that always succeeds.
 * Tracks outcome per LRA so concurrent tests get correct @Status responses.
 */
@ApplicationScoped
@Path(SimpleParticipant.PATH)
public class SimpleParticipant {

    public static final String PATH = "ha-test-participant";

    private static final Map<URI, ParticipantStatus> outcomes = new ConcurrentHashMap<>();

    @PUT
    @Path("complete")
    @Produces(MediaType.TEXT_PLAIN)
    @Complete
    public Response complete(@HeaderParam(LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        outcomes.put(lraId, ParticipantStatus.Completed);
        return Response.ok(ParticipantStatus.Completed.name()).build();
    }

    @PUT
    @Path("compensate")
    @Produces(MediaType.TEXT_PLAIN)
    @Compensate
    public Response compensate(@HeaderParam(LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        outcomes.put(lraId, ParticipantStatus.Compensated);
        return Response.ok(ParticipantStatus.Compensated.name()).build();
    }

    @GET
    @Path("status")
    @Produces(MediaType.TEXT_PLAIN)
    @Status
    public Response status(@HeaderParam(LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        ParticipantStatus status = outcomes.getOrDefault(lraId, ParticipantStatus.Active);
        return Response.ok(status.name()).build();
    }

    @DELETE
    @Path("forget")
    @Forget
    public Response forget(@HeaderParam(LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        outcomes.remove(lraId);
        return Response.ok().build();
    }

    public static int getCompleteCount() {
        return (int) outcomes.values().stream()
                .filter(s -> s == ParticipantStatus.Completed).count();
    }

    public static int getCompensateCount() {
        return (int) outcomes.values().stream()
                .filter(s -> s == ParticipantStatus.Compensated).count();
    }

    public static void resetCounts() {
        outcomes.clear();
    }
}
