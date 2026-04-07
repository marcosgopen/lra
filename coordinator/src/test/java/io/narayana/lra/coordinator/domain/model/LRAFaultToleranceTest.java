/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.domain.model;

import static io.narayana.lra.LRAConstants.COORDINATOR_PATH_NAME;
import static jakarta.ws.rs.core.Response.Status.NOT_FOUND;
import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.LRA_HTTP_RECOVERY_HEADER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.narayana.lra.LRAConstants;
import io.narayana.lra.client.NarayanaLRAClient;
import io.narayana.lra.coordinator.api.Coordinator;
import io.narayana.lra.coordinator.internal.LRARecoveryModule;
import io.narayana.lra.filter.ServerLRAFilter;
import io.narayana.lra.logging.LRALogger;
import io.narayana.lra.provider.ParticipantStatusOctetStreamProvider;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.lra.annotation.Compensate;
import org.eclipse.microprofile.lra.annotation.Complete;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.eclipse.microprofile.lra.annotation.ParticipantStatus;
import org.eclipse.microprofile.lra.annotation.ws.rs.LRA;
import org.jboss.byteman.contrib.bmunit.BMRule;
import org.jboss.byteman.contrib.bmunit.BMRules;
import org.jboss.byteman.contrib.bmunit.WithByteman;
import org.jboss.resteasy.plugins.server.undertow.UndertowJaxrsServer;
import org.jboss.resteasy.test.TestPortProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

/**
 * Fault tolerance tests for the LRA coordinator.
 *
 * Tests cover:
 * 1. Lock acquisition failures during close/cancel
 * 2. Participant unreachable during close/cancel
 * 3. Duplicate close/cancel/enlistment messages
 * 4. leaveLRA failure scenarios
 * 5. Out-of-spec participant responses (garbage body, wrong HTTP code, empty body) on both close and cancel paths
 * 6. Concurrent close and cancel on the same LRA
 * 7. getStatus on non-existent or terminated LRAs
 * 8. setCurrentLRA with invalid URIs
 * 9. Participant network timeout during close/cancel
 */
@WithByteman
public class LRAFaultToleranceTest extends LRATestBase {

    private NarayanaLRAClient lraClient;
    private Client client;
    private int[] ports = { 8081, 8082 };
    public String testName;

    // ===================================================================
    // Out-of-spec participant classes
    // ===================================================================

    /**
     * A participant whose complete and compensate endpoints return HTTP 200
     * with a body that is not a valid ParticipantStatus value.
     */
    @Path("/garbage-test")
    public static class GarbageResponseParticipant {
        @PUT
        @Path("/complete")
        @Complete
        public Response complete(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI contextLRA) {
            return Response.ok("THIS_IS_NOT_A_VALID_STATUS").build();
        }

        @PUT
        @Path("/compensate")
        @Compensate
        public Response compensate(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI contextLRA) {
            return Response.ok("THIS_IS_NOT_A_VALID_STATUS").build();
        }
    }

    /**
     * A participant whose complete and compensate endpoints return an HTTP
     * status code not defined by the LRA protocol (418 I'm a teapot).
     */
    @Path("/wrong-code-test")
    public static class WrongStatusCodeParticipant {
        @PUT
        @Path("/complete")
        @Complete
        public Response complete(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI contextLRA) {
            return Response.status(418).entity("I'm a teapot").build();
        }

        @PUT
        @Path("/compensate")
        @Compensate
        public Response compensate(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI contextLRA) {
            return Response.status(418).entity("I'm a teapot").build();
        }
    }

    /**
     * A participant whose complete and compensate endpoints return HTTP 200
     * with an empty body (no ParticipantStatus).
     */
    @Path("/empty-response-test")
    public static class EmptyResponseParticipant {
        @PUT
        @Path("/complete")
        @Complete
        public Response complete(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI contextLRA) {
            return Response.ok("").build();
        }

        @PUT
        @Path("/compensate")
        @Compensate
        public Response compensate(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI contextLRA) {
            return Response.ok("").build();
        }
    }

    /**
     * A participant whose complete and compensate endpoints sleep longer than
     * the coordinator's PARTICIPANT_TIMEOUT (2 seconds), simulating a network
     * request timeout.
     */
    @Path("/slow-test")
    public static class SlowResponseParticipant {
        @PUT
        @Path("/complete")
        @Complete
        public Response complete(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI contextLRA) {
            try {
                TimeUnit.SECONDS.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Response.ok(ParticipantStatus.Completed.name()).build();
        }

        @PUT
        @Path("/compensate")
        @Compensate
        public Response compensate(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI contextLRA) {
            try {
                TimeUnit.SECONDS.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Response.ok(ParticipantStatus.Compensated.name()).build();
        }
    }

    // ===================================================================
    // Application classes
    // ===================================================================

    @ApplicationPath("base")
    public static class FaultToleranceParticipantApp extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            HashSet<Class<?>> classes = new HashSet<>();
            classes.add(Participant.class);
            classes.add(GarbageResponseParticipant.class);
            classes.add(WrongStatusCodeParticipant.class);
            classes.add(EmptyResponseParticipant.class);
            classes.add(SlowResponseParticipant.class);
            classes.add(ServerLRAFilter.class);
            classes.add(ParticipantStatusOctetStreamProvider.class);
            classes.add(BytemanHelper.class);
            return classes;
        }
    }

    @ApplicationPath("/")
    public static class LRACoordinatorApp extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            HashSet<Class<?>> classes = new HashSet<>();
            classes.add(Coordinator.class);
            return classes;
        }
    }

    @BeforeEach
    public void before(TestInfo testInfo) {
        Optional<Method> testMethod = testInfo.getTestMethod();
        this.testName = testMethod.get().getName();
        LRALogger.logger.debugf("Starting test %s", testName);
        clearObjectStore(testName);

        servers = new UndertowJaxrsServer[ports.length];

        StringBuilder sb = new StringBuilder();
        String host = "localhost";

        for (int i = 0; i < ports.length; i++) {
            servers[i] = new UndertowJaxrsServer().setHostname(host).setPort(ports[i]);
            try {
                servers[i].start();
            } catch (Exception e) {
                LRALogger.logger.infof("before test %s: could not start server %s", testName, e.getMessage());
            }

            sb.append(String.format("http://%s:%d/%s%s",
                    host, ports[i], COORDINATOR_PATH_NAME, i + 1 < ports.length ? "," : ""));
        }

        System.setProperty(NarayanaLRAClient.LRA_COORDINATOR_URL_KEY, sb.toString());

        lraClient = new NarayanaLRAClient();
        client = ClientBuilder.newClient();

        compensateCount.set(0);
        completeCount.set(0);
        forgetCount.set(0);

        for (UndertowJaxrsServer server : servers) {
            server.deploy(LRACoordinatorApp.class);
            server.deployOldStyle(FaultToleranceParticipantApp.class);
        }

        LRARecoveryModule.getService();

        if (lraClient.getCurrent() != null) {
            LRALogger.logger.warnf("before test %s: current thread should not be associated with any LRAs", testName);
            lraClient.clearCurrent(true);
        }
    }

    @AfterEach
    public void after() {
        URI uri = lraClient.getCurrent();
        try {
            if (uri != null) {
                lraClient.clearCurrent(false);
            }
            lraClient.close();
            client.close();
            clearObjectStore(testName);
        } catch (Exception e) {
            LRALogger.logger.infof("after test %s: clean up %s", testName, e.getMessage());
        } finally {
            BytemanHelper.clearFlag("fail-lock");
            for (UndertowJaxrsServer server : servers) {
                try {
                    server.stop();
                } catch (Exception e) {
                    LRALogger.logger.infof("after test %s: could not stop server %s", testName, e.getMessage());
                }
            }
        }
    }

    // ===================================================================
    // Helpers
    // ===================================================================

    private LRAStatus getStatus(URI lra) {
        try {
            return lraClient.getStatus(lra);
        } catch (NotFoundException ignore) {
            return null;
        } catch (WebApplicationException e) {
            if (e.getResponse().getStatus() == NOT_FOUND.getStatusCode()) {
                return null;
            }
            throw e;
        }
    }

    private void enlistParticipant(URI lraId) {
        enlistParticipantAtPath(lraId, "/base/test");
    }

    private void enlistParticipantAtPath(URI lraId, String pathPrefix) {
        String lraUrl = lraId.toASCIIString().split("\\?")[0];
        String prefix = TestPortProvider.generateURL(pathPrefix);
        String linkHeader = String.join(",",
                makeLink(prefix, "complete"),
                makeLink(prefix, "compensate"));

        try (Response response = client.target(lraUrl).request().put(Entity.text(linkHeader))) {
            assertEquals(200, response.getStatus(),
                    "Unexpected status enlisting participant: " + response.readEntity(String.class));
            String recoveryId = response.getHeaderString(LRA_HTTP_RECOVERY_HEADER);
            assertNotNull(recoveryId, "recovery id was null");
        }
    }

    private void enlistUnreachableParticipant(URI lraId) {
        String lraUrl = lraId.toASCIIString().split("\\?")[0];
        String prefix = "http://localhost:39999/unreachable";
        String linkHeader = String.join(",",
                makeLink(prefix, "complete"),
                makeLink(prefix, "compensate"));

        try (Response response = client.target(lraUrl).request().put(Entity.text(linkHeader))) {
            assertEquals(200, response.getStatus(),
                    "Unexpected status enlisting unreachable participant: " + response.readEntity(String.class));
        }
    }

    /**
     * Call the coordinator close endpoint directly and return the raw Response
     * so that the caller can assert the HTTP status code.
     */
    private Response rawCloseLRA(URI lraId) {
        String lraUrl = lraId.toASCIIString().split("\\?")[0];
        return client.target(String.format("%s/close", lraUrl))
                .request()
                .put(Entity.text(""));
    }

    /**
     * Call the coordinator cancel endpoint directly and return the raw Response
     * so that the caller can assert the HTTP status code.
     */
    private Response rawCancelLRA(URI lraId) {
        String lraUrl = lraId.toASCIIString().split("\\?")[0];
        return client.target(String.format("%s/cancel", lraUrl))
                .request()
                .put(Entity.text(""));
    }

    private Response rawCloseLRAWithVersion(URI lraId, String apiVersion) {
        String lraUrl = lraId.toASCIIString().split("\\?")[0];
        return client.target(String.format("%s/close", lraUrl))
                .request()
                .header(LRAConstants.NARAYANA_LRA_API_VERSION_HEADER_NAME, apiVersion)
                .put(Entity.text(""));
    }

    private Response rawCancelLRAWithVersion(URI lraId, String apiVersion) {
        String lraUrl = lraId.toASCIIString().split("\\?")[0];
        return client.target(String.format("%s/cancel", lraUrl))
                .request()
                .header(LRAConstants.NARAYANA_LRA_API_VERSION_HEADER_NAME, apiVersion)
                .put(Entity.text(""));
    }

    private Response rawGetStatusWithVersion(URI lraId, String apiVersion) {
        String lraUrl = lraId.toASCIIString().split("\\?")[0];
        return client.target(String.format("%s/status", lraUrl))
                .request()
                .header(LRAConstants.NARAYANA_LRA_API_VERSION_HEADER_NAME, apiVersion)
                .get();
    }

    private static String makeLink(String uriPrefix, String key) {
        return Link.fromUri(String.format("%s/%s", uriPrefix, key))
                .title(key + " URI")
                .rel(key)
                .type(MediaType.TEXT_PLAIN)
                .build().toString();
    }

    // ===================================================================
    // 1. Lock Acquisition Failure Tests
    // ===================================================================

    /**
     * When the coordinator cannot acquire the lock during close (because another
     * thread is already finishing the LRA), finishLRA should return without
     * modifying the LRA. The coordinator should return 503 (Service Unavailable)
     * for v2.0 clients to signal "retry later", and the LRA must remain Active.
     * A subsequent close (after the lock becomes available) must succeed.
     */
    @Test
    @BMRules(rules = {
            @BMRule(name = "fail lock acquisition", targetClass = "io.narayana.lra.coordinator.domain.service.LRAService", targetMethod = "tryLockTransaction(java.net.URI)", targetLocation = "AT ENTRY", condition = "io.narayana.lra.coordinator.domain.model.BytemanHelper.isFlagSet(\"fail-lock\")", action = "RETURN null")
    })
    public void testLockAcquisitionFailureDuringClose() {
        URI lraId = lraClient.startLRA(testName);
        enlistParticipant(lraId);
        assertEquals(LRAStatus.Active, getStatus(lraId), "LRA should start Active");

        BytemanHelper.setFlag("fail-lock");
        try {
            try (Response response = rawCloseLRAWithVersion(lraId, LRAConstants.API_VERSION_2_0)) {
                assertEquals(503, response.getStatus(),
                        "Close with failed lock should return 503 (retry later)");
            }
            LRAStatus status = getStatus(lraId);
            assertEquals(LRAStatus.Active, status,
                    "LRA should remain Active when lock acquisition fails during close");
        } finally {
            BytemanHelper.clearFlag("fail-lock");
        }

        // now close for real
        try (Response response = rawCloseLRA(lraId)) {
            assertEquals(200, response.getStatus(), "Close after lock restored should return 200");
        }
        LRAStatus finalStatus = getStatus(lraId);
        assertTrue(finalStatus == null || finalStatus == LRAStatus.Closed,
                "LRA should be Closed after successful close");
    }

    /**
     * Same as {@link #testLockAcquisitionFailureDuringClose} but for cancel.
     */
    @Test
    @BMRules(rules = {
            @BMRule(name = "fail lock acquisition", targetClass = "io.narayana.lra.coordinator.domain.service.LRAService", targetMethod = "tryLockTransaction(java.net.URI)", targetLocation = "AT ENTRY", condition = "io.narayana.lra.coordinator.domain.model.BytemanHelper.isFlagSet(\"fail-lock\")", action = "RETURN null")
    })
    public void testLockAcquisitionFailureDuringCancel() {
        URI lraId = lraClient.startLRA(testName);
        enlistParticipant(lraId);
        assertEquals(LRAStatus.Active, getStatus(lraId), "LRA should start Active");

        BytemanHelper.setFlag("fail-lock");
        try {
            try (Response response = rawCancelLRAWithVersion(lraId, LRAConstants.API_VERSION_2_0)) {
                assertEquals(503, response.getStatus(),
                        "Cancel with failed lock should return 503 (retry later)");
            }
            LRAStatus status = getStatus(lraId);
            assertEquals(LRAStatus.Active, status,
                    "LRA should remain Active when lock acquisition fails during cancel");
        } finally {
            BytemanHelper.clearFlag("fail-lock");
        }

        try (Response response = rawCancelLRA(lraId)) {
            assertEquals(200, response.getStatus(), "Cancel after lock restored should return 200");
        }
        LRAStatus finalStatus = getStatus(lraId);
        assertTrue(finalStatus == null || finalStatus == LRAStatus.Cancelled,
                "LRA should be Cancelled after successful cancel");
    }

    // ===================================================================
    // 2. Participant Unreachable Tests
    // ===================================================================

    /**
     * When a participant's complete endpoint is unreachable (connection refused),
     * the coordinator should put the LRA into recovery rather than marking it
     * as successfully closed.
     */
    @Test
    public void testParticipantUnreachableDuringClose() {
        URI lraId = lraClient.startLRA(testName);
        enlistUnreachableParticipant(lraId);

        try (Response response = rawCloseLRAWithVersion(lraId, LRAConstants.API_VERSION_2_0)) {
            assertEquals(202, response.getStatus(),
                    "Close should return 202 when participant is unreachable");
        }

        LRAStatus status = getStatus(lraId);
        assertNotNull(status, "LRA should still exist when participant is unreachable");
        assertTrue(status == LRAStatus.Closing || status == LRAStatus.FailedToClose,
                "LRA should be Closing or FailedToClose when participant is unreachable, but was " + status);
    }

    /**
     * When a participant's compensate endpoint is unreachable (connection refused),
     * the coordinator should put the LRA into recovery rather than marking it
     * as successfully cancelled.
     */
    @Test
    public void testParticipantUnreachableDuringCancel() {
        URI lraId = lraClient.startLRA(testName);
        enlistUnreachableParticipant(lraId);

        try (Response response = rawCancelLRAWithVersion(lraId, LRAConstants.API_VERSION_2_0)) {
            assertEquals(202, response.getStatus(),
                    "Cancel should return 202 when participant is unreachable");
        }

        LRAStatus status = getStatus(lraId);
        assertNotNull(status, "LRA should still exist when participant is unreachable");
        assertTrue(status == LRAStatus.Cancelling || status == LRAStatus.FailedToCancel,
                "LRA should be Cancelling or FailedToCancel when participant is unreachable, but was " + status);
    }

    // ===================================================================
    // 2b. Location Header on 202 Responses
    // ===================================================================

    /**
     * When close returns 202, the response must include a Location header
     * pointing to the status endpoint. The URL in the Location header must
     * be functional: a GET on it must return the current LRA status.
     */
    @Test
    public void testLocationHeaderOnCloseAccepted() {
        URI lraId = lraClient.startLRA(testName);
        enlistUnreachableParticipant(lraId);

        try (Response closeResponse = rawCloseLRAWithVersion(lraId, LRAConstants.API_VERSION_2_0)) {
            assertEquals(202, closeResponse.getStatus(),
                    "Close should return 202 when participant is unreachable");

            URI location = closeResponse.getLocation();
            assertNotNull(location, "202 response must include a Location header");
            assertTrue(location.toASCIIString().contains("/status"),
                    "Location header should point to the status endpoint, but was: " + location);

            // the Location URL must be functional - GET on it returns 200 with the current status
            try (Response statusResponse = client.target(location).request().get()) {
                assertEquals(200, statusResponse.getStatus(),
                        "GET on Location URL should return 200");
                String statusBody = statusResponse.readEntity(String.class);
                assertNotNull(statusBody, "Status response body should not be null");
                LRAStatus polledStatus = LRAStatus.valueOf(statusBody);
                assertTrue(polledStatus == LRAStatus.Closing || polledStatus == LRAStatus.FailedToClose,
                        "Polled status should be Closing or FailedToClose, but was " + polledStatus);
            }
        }
    }

    /**
     * Same as {@link #testLocationHeaderOnCloseAccepted()} but for cancel.
     */
    @Test
    public void testLocationHeaderOnCancelAccepted() {
        URI lraId = lraClient.startLRA(testName);
        enlistUnreachableParticipant(lraId);

        try (Response cancelResponse = rawCancelLRAWithVersion(lraId, LRAConstants.API_VERSION_2_0)) {
            assertEquals(202, cancelResponse.getStatus(),
                    "Cancel should return 202 when participant is unreachable");

            URI location = cancelResponse.getLocation();
            assertNotNull(location, "202 response must include a Location header");
            assertTrue(location.toASCIIString().contains("/status"),
                    "Location header should point to the status endpoint, but was: " + location);

            // the Location URL must be functional
            try (Response statusResponse = client.target(location).request().get()) {
                assertEquals(200, statusResponse.getStatus(),
                        "GET on Location URL should return 200");
                String statusBody = statusResponse.readEntity(String.class);
                assertNotNull(statusBody, "Status response body should not be null");
                LRAStatus polledStatus = LRAStatus.valueOf(statusBody);
                assertTrue(polledStatus == LRAStatus.Cancelling || polledStatus == LRAStatus.FailedToCancel,
                        "Polled status should be Cancelling or FailedToCancel, but was " + polledStatus);
            }
        }
    }

    /**
     * When close returns 200 (terminal state), the Location header should not be
     * present since there is nothing to poll.
     */
    @Test
    public void testNoLocationHeaderOnCloseSuccess() {
        URI lraId = lraClient.startLRA(testName);
        enlistParticipant(lraId);

        try (Response closeResponse = rawCloseLRA(lraId)) {
            assertEquals(200, closeResponse.getStatus(),
                    "Close should return 200 when all participants respond");
            assertNull(closeResponse.getLocation(),
                    "200 response should not include a Location header");
        }
    }

    // ===================================================================
    // 3. Duplicate Message Tests
    // ===================================================================

    /**
     * Closing an LRA that has already been closed should return a predictable
     * error (404 or 412) rather than causing a coordinator failure.
     */
    @Test
    public void testDuplicateClose() {
        URI lraId = lraClient.startLRA(testName);
        enlistParticipant(lraId);

        try (Response response = rawCloseLRA(lraId)) {
            assertEquals(200, response.getStatus(), "First close should return 200");
        }
        LRAStatus statusAfterFirst = getStatus(lraId);
        assertTrue(statusAfterFirst == null || statusAfterFirst == LRAStatus.Closed,
                "LRA should be Closed after first close");

        // second close: LRA is gone or in terminal state, must be rejected
        try (Response response = rawCloseLRA(lraId)) {
            assertTrue(response.getStatus() == 404 || response.getStatus() == 412,
                    "Duplicate close should return 404 or 412, got " + response.getStatus());
        }
    }

    /**
     * Cancelling an LRA that has already been cancelled should return a predictable
     * error (404 or 412) rather than causing a coordinator failure.
     */
    @Test
    public void testDuplicateCancel() {
        URI lraId = lraClient.startLRA(testName);
        enlistParticipant(lraId);

        try (Response response = rawCancelLRA(lraId)) {
            assertEquals(200, response.getStatus(), "First cancel should return 200");
        }
        LRAStatus statusAfterFirst = getStatus(lraId);
        assertTrue(statusAfterFirst == null || statusAfterFirst == LRAStatus.Cancelled,
                "LRA should be Cancelled after first cancel");

        try (Response response = rawCancelLRA(lraId)) {
            assertTrue(response.getStatus() == 404 || response.getStatus() == 412,
                    "Duplicate cancel should return 404 or 412, got " + response.getStatus());
        }
    }

    /**
     * Enlisting the same participant twice with the same link header should be
     * idempotent: the coordinator returns the existing participant record and
     * the participant is only called once when the LRA terminates.
     */
    @Test
    public void testDuplicateEnlistment() {
        URI lraId = lraClient.startLRA(testName);
        enlistParticipant(lraId);
        enlistParticipant(lraId); // same participant, should be idempotent

        try (Response response = rawCloseLRA(lraId)) {
            assertEquals(200, response.getStatus(), "Close after duplicate enlistment should return 200");
        }
        assertEquals(1, completeCount.get(),
                "Duplicate enlistment should not cause double completion");
    }

    // ===================================================================
    // 4. leaveLRA Failure Tests
    // ===================================================================

    /**
     * Attempting to leave an LRA with a participant URL that was never enrolled
     * should return HTTP 400 (Bad Request).
     */
    @Test
    public void testLeaveWithInvalidParticipant() {
        URI lraId = lraClient.startLRA(testName);
        enlistParticipant(lraId);

        String lraUrl = lraId.toASCIIString().split("\\?")[0];

        // try to leave with a participant URL that was never enrolled
        try (Response response = client.target(String.format("%s/remove", lraUrl))
                .request()
                .put(Entity.text("http://nonexistent:99999/not-enrolled"))) {
            assertEquals(400, response.getStatus(),
                    "Leave with unenrolled participant should return 400");
        }

        // the original participant should still be enlisted
        try (Response closeResponse = rawCloseLRA(lraId)) {
            assertEquals(200, closeResponse.getStatus(),
                    "Close after failed leave should return 200");
        }
        assertEquals(1, completeCount.get(),
                "Original participant should still be completed after failed leave");
    }

    /**
     * Attempting to leave an LRA that is no longer active (already closed)
     * should return HTTP 404 (not found) or 412 (precondition failed).
     */
    @Test
    public void testLeaveFromNonActiveLRA() {
        URI lraId = lraClient.startLRA(testName);
        enlistParticipant(lraId);

        try (Response closeResponse = rawCloseLRA(lraId)) {
            assertEquals(200, closeResponse.getStatus(), "Close should return 200");
        }

        String lraUrl = lraId.toASCIIString().split("\\?")[0];

        // try to leave from the now-closed LRA
        try (Response response = client.target(String.format("%s/remove", lraUrl))
                .request()
                .put(Entity.text("http://localhost/some-participant"))) {
            assertTrue(response.getStatus() == 404 || response.getStatus() == 412,
                    "Leave from non-active LRA should return 404 or 412, got " + response.getStatus());
        }
    }

    // ===================================================================
    // 5. Out-of-Spec Participant Response Tests
    // ===================================================================

    /**
     * A participant that returns HTTP 200 with a body that is not a valid
     * ParticipantStatus string during close. The coordinator must handle this
     * gracefully without crashing.
     */
    @Test
    public void testOutOfSpecGarbageBodyDuringClose() {
        URI lraId = lraClient.startLRA(testName);
        enlistParticipantAtPath(lraId, "/base/garbage-test");

        try (Response response = rawCloseLRA(lraId)) {
            assertEquals(200, response.getStatus(),
                    "Close with garbage response should return 200");
        }

        // the coordinator should not have crashed
        LRAStatus status = getStatus(lraId);
        assertTrue(status == null || status == LRAStatus.Closed
                || status == LRAStatus.Closing || status == LRAStatus.FailedToClose,
                "Coordinator should handle garbage participant body gracefully, but status was " + status);
    }

    /**
     * Same as {@link #testOutOfSpecGarbageBodyDuringClose} but for the compensate path.
     */
    @Test
    public void testOutOfSpecGarbageBodyDuringCancel() {
        URI lraId = lraClient.startLRA(testName);
        enlistParticipantAtPath(lraId, "/base/garbage-test");

        try (Response response = rawCancelLRA(lraId)) {
            assertEquals(200, response.getStatus(),
                    "Cancel with garbage response should return 200");
        }

        LRAStatus status = getStatus(lraId);
        assertTrue(status == null || status == LRAStatus.Cancelled
                || status == LRAStatus.Cancelling || status == LRAStatus.FailedToCancel,
                "Coordinator should handle garbage participant body gracefully during cancel, but status was " + status);
    }

    /**
     * A participant that returns an HTTP status code not defined by the LRA
     * protocol (418 I'm a teapot) during close. The coordinator must handle
     * this gracefully.
     */
    @Test
    public void testOutOfSpecWrongStatusCodeDuringClose() {
        URI lraId = lraClient.startLRA(testName);
        enlistParticipantAtPath(lraId, "/base/wrong-code-test");

        try (Response response = rawCloseLRA(lraId)) {
            assertEquals(200, response.getStatus(),
                    "Close with wrong status code should return 200");
        }

        LRAStatus status = getStatus(lraId);
        assertTrue(status == null || status == LRAStatus.Closed
                || status == LRAStatus.Closing || status == LRAStatus.FailedToClose,
                "Coordinator should handle unexpected HTTP status code gracefully, but status was " + status);
    }

    /**
     * Same as {@link #testOutOfSpecWrongStatusCodeDuringClose} but for the compensate path.
     */
    @Test
    public void testOutOfSpecWrongStatusCodeDuringCancel() {
        URI lraId = lraClient.startLRA(testName);
        enlistParticipantAtPath(lraId, "/base/wrong-code-test");

        try (Response response = rawCancelLRA(lraId)) {
            assertEquals(200, response.getStatus(),
                    "Cancel with wrong status code should return 200");
        }

        LRAStatus status = getStatus(lraId);
        assertTrue(status == null || status == LRAStatus.Cancelled
                || status == LRAStatus.Cancelling || status == LRAStatus.FailedToCancel,
                "Coordinator should handle unexpected HTTP status code gracefully during cancel, but status was " + status);
    }

    /**
     * A participant that returns HTTP 200 with an empty body (no ParticipantStatus)
     * during close. The coordinator must handle this gracefully.
     */
    @Test
    public void testOutOfSpecEmptyBodyDuringClose() {
        URI lraId = lraClient.startLRA(testName);
        enlistParticipantAtPath(lraId, "/base/empty-response-test");

        try (Response response = rawCloseLRA(lraId)) {
            assertEquals(200, response.getStatus(),
                    "Close with empty body should return 200");
        }

        LRAStatus status = getStatus(lraId);
        assertTrue(status == null || status == LRAStatus.Closed
                || status == LRAStatus.Closing || status == LRAStatus.FailedToClose,
                "Coordinator should handle empty participant response body gracefully, but status was " + status);
    }

    /**
     * Same as {@link #testOutOfSpecEmptyBodyDuringClose} but for the compensate path.
     */
    @Test
    public void testOutOfSpecEmptyBodyDuringCancel() {
        URI lraId = lraClient.startLRA(testName);
        enlistParticipantAtPath(lraId, "/base/empty-response-test");

        try (Response response = rawCancelLRA(lraId)) {
            assertEquals(200, response.getStatus(),
                    "Cancel with empty body should return 200");
        }

        LRAStatus status = getStatus(lraId);
        assertTrue(status == null || status == LRAStatus.Cancelled
                || status == LRAStatus.Cancelling || status == LRAStatus.FailedToCancel,
                "Coordinator should handle empty participant response body gracefully during cancel, but status was " + status);
    }

    // ===================================================================
    // 6. Concurrent Close and Cancel Test
    // ===================================================================

    /**
     * Two threads concurrently attempt to close and cancel the same LRA.
     * One operation should win and the other should either succeed (if the LRA
     * is still Active) or fail with a predictable error. The LRA must end up
     * in a consistent terminal state. This test verifies that the coordinator's
     * locking strategy prevents corruption under concurrent access.
     *
     * Note: both threads share the same {@code lraClient} instance. This is safe
     * because {@code closeLRA}/{@code cancelLRA} use the LRA id parameter directly
     * and the thread-local {@code Current} context is not relevant for these operations.
     */
    @Test
    public void testConcurrentCloseAndCancel() throws InterruptedException {
        URI lraId = lraClient.startLRA(testName);
        enlistParticipant(lraId);

        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicReference<Integer> closeStatus = new AtomicReference<>();
        AtomicReference<Integer> cancelStatus = new AtomicReference<>();
        AtomicReference<Throwable> closeError = new AtomicReference<>();
        AtomicReference<Throwable> cancelError = new AtomicReference<>();

        Thread closeThread = new Thread(() -> {
            try {
                startLatch.await();
                try (Response response = rawCloseLRA(lraId)) {
                    closeStatus.set(response.getStatus());
                }
            } catch (Throwable t) {
                closeError.set(t);
            }
        });

        Thread cancelThread = new Thread(() -> {
            try {
                startLatch.await();
                try (Response response = rawCancelLRA(lraId)) {
                    cancelStatus.set(response.getStatus());
                }
            } catch (Throwable t) {
                cancelError.set(t);
            }
        });

        closeThread.start();
        cancelThread.start();
        startLatch.countDown(); // release both threads simultaneously

        closeThread.join(10_000);
        cancelThread.join(10_000);

        assertFalse(closeThread.isAlive(), "Close thread should have completed");
        assertFalse(cancelThread.isAlive(), "Cancel thread should have completed");

        if (closeError.get() != null) {
            fail("Close thread threw unexpected error: " + closeError.get().getMessage());
        }
        if (cancelError.get() != null) {
            fail("Cancel thread threw unexpected error: " + cancelError.get().getMessage());
        }

        // both operations should return a valid HTTP status
        assertNotNull(closeStatus.get(), "Close thread should have received a response");
        assertNotNull(cancelStatus.get(), "Cancel thread should have received a response");

        // no version header = legacy behavior (always 200 for success)
        // at least one should succeed (200), the other may get 200 (lock skipped), 404, or 412
        assertTrue(closeStatus.get() == 200 || cancelStatus.get() == 200,
                "At least one of close or cancel should return 200, got close=" + closeStatus.get()
                        + " cancel=" + cancelStatus.get());
        assertTrue(closeStatus.get() == 200 || closeStatus.get() == 404 || closeStatus.get() == 412,
                "Close should return 200, 404, or 412, got " + closeStatus.get());
        assertTrue(cancelStatus.get() == 200 || cancelStatus.get() == 404 || cancelStatus.get() == 412,
                "Cancel should return 200, 404, or 412, got " + cancelStatus.get());

        // LRA should be in a consistent terminal state
        LRAStatus finalStatus = getStatus(lraId);
        assertTrue(finalStatus == null
                || finalStatus == LRAStatus.Closed
                || finalStatus == LRAStatus.Cancelled
                || finalStatus == LRAStatus.FailedToClose
                || finalStatus == LRAStatus.FailedToCancel,
                "LRA should be in a terminal state after concurrent close/cancel, but was " + finalStatus);
    }

    // ===================================================================
    // 7. getStatus Failure Tests
    // ===================================================================

    /**
     * Calling getStatus on a non-existent LRA ID should return 404.
     */
    @Test
    public void testGetStatusNonExistentLRA() {
        URI fakeLra = URI.create(String.format("http://localhost:%d/%s/non-existent-uid",
                ports[0], COORDINATOR_PATH_NAME));
        try {
            LRAStatus status = lraClient.getStatus(fakeLra);
            fail("getStatus on non-existent LRA should throw, but returned " + status);
        } catch (NotFoundException e) {
            // expected: 404
        } catch (WebApplicationException e) {
            assertEquals(Status.NOT_FOUND.getStatusCode(), e.getResponse().getStatus(),
                    "getStatus on non-existent LRA should return 404, got " + e.getResponse().getStatus());
        }
    }

    /**
     * Calling getStatus on an LRA that has been closed should return Closed
     * immediately after the close call, and eventually 404 once the
     * coordinator has cleaned up the LRA record.
     */
    @Test
    public void testGetStatusAfterClose() {
        URI lraId = lraClient.startLRA(testName);
        enlistParticipant(lraId);

        // verify the LRA terminates successfully
        try (Response response = rawCloseLRA(lraId)) {
            assertEquals(200, response.getStatus(), "Close should return 200");
        }

        // immediately after close the coordinator may still know the LRA
        LRAStatus statusRight = getStatus(lraId);
        assertTrue(statusRight == null || statusRight == LRAStatus.Closed,
                "Immediately after close, status should be Closed or null (already removed), but was " + statusRight);

        // a second getStatus call on an already-closed LRA must not crash the coordinator
        LRAStatus statusAgain = getStatus(lraId);
        assertTrue(statusAgain == null || statusAgain == LRAStatus.Closed,
                "Repeated getStatus after close should be stable, but was " + statusAgain);
    }

    /**
     * Calling getStatus on an LRA that has been cancelled should return Cancelled
     * immediately after the cancel call, and eventually 404 once the
     * coordinator has cleaned up the LRA record.
     */
    @Test
    public void testGetStatusAfterCancel() {
        URI lraId = lraClient.startLRA(testName);
        enlistParticipant(lraId);

        try (Response response = rawCancelLRA(lraId)) {
            assertEquals(200, response.getStatus(), "Cancel should return 200");
        }

        LRAStatus statusRight = getStatus(lraId);
        assertTrue(statusRight == null || statusRight == LRAStatus.Cancelled,
                "Immediately after cancel, status should be Cancelled or null (already removed), but was " + statusRight);

        LRAStatus statusAgain = getStatus(lraId);
        assertTrue(statusAgain == null || statusAgain == LRAStatus.Cancelled,
                "Repeated getStatus after cancel should be stable, but was " + statusAgain);
    }

    // ===================================================================
    // 8. setCurrentLRA Failure Tests
    // ===================================================================

    /**
     * Calling setCurrentLRA with a malformed URI (not a valid LRA coordinator
     * URL) should throw an exception rather than silently accepting it.
     */
    @Test
    public void testSetCurrentLRAWithInvalidURI() {
        try {
            lraClient.setCurrentLRA(URI.create("not-a-valid-lra-url"));
            fail("setCurrentLRA with invalid URI should throw");
        } catch (WebApplicationException e) {
            assertEquals(Status.BAD_REQUEST.getStatusCode(), e.getResponse().getStatus(),
                    "setCurrentLRA with invalid URI should return 400");
        } catch (IllegalStateException e) {
            // the URI format is invalid and does not contain the coordinator path
        }
    }

    // ===================================================================
    // 9. Participant Timeout Tests
    // ===================================================================

    /**
     * When a participant's complete endpoint takes longer than PARTICIPANT_TIMEOUT
     * (2 seconds) to respond, the coordinator should treat the participant as
     * in-progress and put the LRA into recovery rather than marking it as
     * successfully closed.
     */
    @Test
    public void testParticipantTimeoutDuringClose() {
        URI lraId = lraClient.startLRA(testName);
        enlistParticipantAtPath(lraId, "/base/slow-test");

        try (Response response = rawCloseLRAWithVersion(lraId, LRAConstants.API_VERSION_2_0)) {
            assertEquals(202, response.getStatus(),
                    "Close should return 202 when participant times out");
        }

        LRAStatus status = getStatus(lraId);
        assertNotNull(status, "LRA should still exist when participant times out");
        assertTrue(status == LRAStatus.Closing || status == LRAStatus.FailedToClose,
                "LRA should be Closing or FailedToClose when participant times out, but was " + status);
    }

    /**
     * When a participant's compensate endpoint takes longer than PARTICIPANT_TIMEOUT
     * (2 seconds) to respond, the coordinator should treat the participant as
     * in-progress and put the LRA into recovery.
     */
    @Test
    public void testParticipantTimeoutDuringCancel() {
        URI lraId = lraClient.startLRA(testName);
        enlistParticipantAtPath(lraId, "/base/slow-test");

        try (Response response = rawCancelLRAWithVersion(lraId, LRAConstants.API_VERSION_2_0)) {
            assertEquals(202, response.getStatus(),
                    "Cancel should return 202 when participant times out");
        }

        LRAStatus status = getStatus(lraId);
        assertNotNull(status, "LRA should still exist when participant times out");
        assertTrue(status == LRAStatus.Cancelling || status == LRAStatus.FailedToCancel,
                "LRA should be Cancelling or FailedToCancel when participant times out, but was " + status);
    }

    // ===================================================================
    // 10. API Version Backward Compatibility Tests
    // ===================================================================

    /**
     * A client sending API version 1.2 should receive 200 (not 202) for close
     * even when the LRA is in a non-terminal state (participant unreachable).
     * This preserves backward compatibility for older clients.
     */
    @Test
    public void testLegacyVersionCloseReturns200ForNonTerminal() {
        URI lraId = lraClient.startLRA(testName);
        enlistUnreachableParticipant(lraId);

        try (Response response = rawCloseLRAWithVersion(lraId, LRAConstants.API_VERSION_1_2)) {
            assertEquals(200, response.getStatus(),
                    "Close with API version 1.2 should return 200 even for non-terminal state");
            String body = response.readEntity(String.class);
            assertTrue(body.contains("Closing") || body.contains("FailedToClose"),
                    "Body should contain the LRA status, but was: " + body);
            assertNull(response.getLocation(),
                    "Legacy 200 response should not include a Location header");
        }
    }

    /**
     * A client sending API version 1.2 should receive 200 (not 202) for cancel
     * even when the LRA is in a non-terminal state.
     */
    @Test
    public void testLegacyVersionCancelReturns200ForNonTerminal() {
        URI lraId = lraClient.startLRA(testName);
        enlistUnreachableParticipant(lraId);

        try (Response response = rawCancelLRAWithVersion(lraId, LRAConstants.API_VERSION_1_2)) {
            assertEquals(200, response.getStatus(),
                    "Cancel with API version 1.2 should return 200 even for non-terminal state");
            String body = response.readEntity(String.class);
            assertTrue(body.contains("Cancelling") || body.contains("FailedToCancel"),
                    "Body should contain the LRA status, but was: " + body);
            assertNull(response.getLocation(),
                    "Legacy 200 response should not include a Location header");
        }
    }

    /**
     * A client sending API version 2.0 should receive 202 for close when the
     * LRA is in a non-terminal state, and the response should include a
     * Location header.
     */
    @Test
    public void testNewVersionCloseReturns202ForNonTerminal() {
        URI lraId = lraClient.startLRA(testName);
        enlistUnreachableParticipant(lraId);

        try (Response response = rawCloseLRAWithVersion(lraId, LRAConstants.API_VERSION_2_0)) {
            assertEquals(202, response.getStatus(),
                    "Close with API version 2.0 should return 202 for non-terminal state");
            URI location = response.getLocation();
            assertNotNull(location,
                    "202 response with API version 2.0 should include a Location header");
            assertTrue(location.toASCIIString().contains("/status"),
                    "Location header should point to the status endpoint, but was: " + location);
        }
    }

    /**
     * A client sending API version 2.0 should receive 202 for cancel when the
     * LRA is in a non-terminal state.
     */
    @Test
    public void testNewVersionCancelReturns202ForNonTerminal() {
        URI lraId = lraClient.startLRA(testName);
        enlistUnreachableParticipant(lraId);

        try (Response response = rawCancelLRAWithVersion(lraId, LRAConstants.API_VERSION_2_0)) {
            assertEquals(202, response.getStatus(),
                    "Cancel with API version 2.0 should return 202 for non-terminal state");
            URI location = response.getLocation();
            assertNotNull(location,
                    "202 response with API version 2.0 should include a Location header");
            assertTrue(location.toASCIIString().contains("/status"),
                    "Location header should point to the status endpoint, but was: " + location);
        }
    }

    /**
     * Both old and new API versions should return 200 when the LRA closes
     * successfully (terminal state). The version difference only matters for
     * non-terminal states.
     */
    @Test
    public void testBothVersionsReturn200ForTerminal() {
        URI lraId1 = lraClient.startLRA(testName + "-v12");
        lraClient.clearCurrent(false); // detach from calling thread before raw close
        enlistParticipant(lraId1);

        try (Response response = rawCloseLRAWithVersion(lraId1, LRAConstants.API_VERSION_1_2)) {
            assertEquals(200, response.getStatus(),
                    "Close with version 1.2 should return 200 for terminal state");
        }

        URI lraId2 = lraClient.startLRA(testName + "-v20");
        lraClient.clearCurrent(false);
        enlistParticipant(lraId2);

        try (Response response = rawCloseLRAWithVersion(lraId2, LRAConstants.API_VERSION_2_0)) {
            assertEquals(200, response.getStatus(),
                    "Close with version 2.0 should also return 200 for terminal state");
        }
    }

    /**
     * The GET /status endpoint always returns 200 regardless of the LRA state
     * or the API version. The status value is conveyed in the response body.
     * This applies to both transitional (Closing/Cancelling) and terminal states.
     */
    @Test
    public void testGetStatusAlwaysReturns200() {
        URI lraId = lraClient.startLRA(testName);
        enlistUnreachableParticipant(lraId);

        // close to put the LRA into a transitional state (Closing)
        rawCloseLRA(lraId).close();

        // status should return 200 even for transitional states, regardless of version
        try (Response response = rawGetStatusWithVersion(lraId, LRAConstants.API_VERSION_1_2)) {
            assertEquals(200, response.getStatus(),
                    "GET /status should return 200 for transitional state with version 1.2");
            String body = response.readEntity(String.class);
            assertTrue(body.contains("Closing") || body.contains("FailedToClose"),
                    "Body should contain the LRA status, but was: " + body);
        }

        try (Response response = rawGetStatusWithVersion(lraId, LRAConstants.API_VERSION_2_0)) {
            assertEquals(200, response.getStatus(),
                    "GET /status should return 200 for transitional state with version 2.0");
            String body = response.readEntity(String.class);
            assertTrue(body.contains("Closing") || body.contains("FailedToClose"),
                    "Body should contain the LRA status, but was: " + body);
        }
    }

}
