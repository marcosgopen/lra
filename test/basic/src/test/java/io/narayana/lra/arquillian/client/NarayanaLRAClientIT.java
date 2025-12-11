/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian.client;

import io.narayana.lra.LRAData;
import io.narayana.lra.arquillian.TestBase;
import io.narayana.lra.client.internal.NarayanaLRAClient;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.jboss.logging.Logger;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class NarayanaLRAClientIT extends TestBase {

    private static final Logger log = Logger.getLogger(NarayanaLRAClientIT.class);

    @Rule
    public TestName testName = new TestName();

    @Override
    public void before() {
        super.before();
        log.info("Running test " + testName.getMethodName());
    }

    @Test
    public void testGetAllLRAs() {
        URI lra = lraClient.startLRA("test-lra");
        lrasToAfterFinish.add(lra);

        List<LRAData> allLRAs = lraClient.getAllLRAs();
        Assert.assertTrue("Expected to find the LRA " + lra + " amongst all active ones: " + allLRAs,
                allLRAs.stream().anyMatch(lraData -> lraData.getLraId().equals(lra)));

        lraClient.closeLRA(lra);

        allLRAs = lraClient.getAllLRAs();
        Assert.assertTrue("LRA " + lra + " was closed but is still referred as active one at: " + allLRAs,
                allLRAs.stream().noneMatch(lraData -> lraData.getLraId().equals(lra)));
    }

    @Test
    public void testStartLRAWithClientId() {
        URI lra = lraClient.startLRA("test-client-id");
        Assert.assertNotNull("LRA should be created successfully", lra);
        lrasToAfterFinish.add(lra);

        LRAStatus status = lraClient.getStatus(lra);
        Assert.assertEquals("LRA should be in Active status", LRAStatus.Active, status);
    }

    @Test
    public void testStartLRAWithTimeout() {
        URI lra = lraClient.startLRA(null, "test-client-timeout", 30000L, ChronoUnit.MILLIS);
        Assert.assertNotNull("LRA with timeout should be created successfully", lra);
        lrasToAfterFinish.add(lra);

        LRAStatus status = lraClient.getStatus(lra);
        Assert.assertEquals("LRA should be in Active status", LRAStatus.Active, status);
    }

    @Test
    public void testStartLRAWithParent() {
        URI parentLra = lraClient.startLRA("parent-lra");

        URI childLra = lraClient.startLRA(parentLra, "child-lra", 10000L, ChronoUnit.MILLIS);
        Assert.assertNotNull("Child LRA should be created successfully", childLra);
        lrasToAfterFinish.add(childLra);
        // finishing the child first or finish the parent only
        lrasToAfterFinish.add(parentLra);

        LRAStatus childStatus = lraClient.getStatus(childLra);
        Assert.assertEquals("Child LRA should be in Active status", LRAStatus.Active, childStatus);
    }

    @Test
    public void testCloseLRA() {
        URI lra = lraClient.startLRA("test-close-lra");
        lrasToAfterFinish.add(lra);

        LRAStatus status = lraClient.getStatus(lra);
        Assert.assertEquals("LRA should be in Active status", LRAStatus.Active, status);

        lraClient.closeLRA(lra);
    }

    @Test
    public void testCloseLRAWithCompensatorAndUserData() {
        URI lra = lraClient.startLRA("test-close-lra-with-data");
        lrasToAfterFinish.add(lra);

        lraClient.closeLRA(lra, "test-compensator", "test-user-data");
    }

    @Test
    public void testCancelLRA() {
        URI lra = lraClient.startLRA("test-cancel-lra");
        lrasToAfterFinish.add(lra);

        LRAStatus status = lraClient.getStatus(lra);
        Assert.assertEquals("LRA should be in Active status", LRAStatus.Active, status);

        lraClient.cancelLRA(lra);
    }

    @Test
    public void testCancelLRAWithCompensatorAndUserData() {
        URI lra = lraClient.startLRA("test-cancel-lra-with-data");
        lrasToAfterFinish.add(lra);

        lraClient.cancelLRA(lra, "test-compensator", "test-user-data");
    }

    @Test
    public void testGetStatus() {
        URI lra = lraClient.startLRA("test-get-status");
        lrasToAfterFinish.add(lra);

        LRAStatus status = lraClient.getStatus(lra);
        Assert.assertEquals("LRA should be in Active status", LRAStatus.Active, status);

        lraClient.closeLRA(lra);
    }

    @Test
    public void testGetLRAInfo() {
        URI lra = lraClient.startLRA("test-get-info");
        lrasToAfterFinish.add(lra);

        Response infoResponse = lraClient.getLRAInfo(lra);
        Assert.assertNotNull("LRA info response should not be null", infoResponse);
        Assert.assertEquals("Response should be successful", Response.Status.OK.getStatusCode(), infoResponse.getStatus());
    }

    @Test
    public void testGetLRAInfoWithMediaType() {
        URI lra = lraClient.startLRA("test-get-info-media-type");
        lrasToAfterFinish.add(lra);

        Response infoResponse = lraClient.getLRAInfo(lra, "application/json");
        Assert.assertNotNull("LRA info response should not be null", infoResponse);
        Assert.assertEquals("Response should be successful", Response.Status.OK.getStatusCode(), infoResponse.getStatus());
    }

    @Test
    public void testRenewTimeLimit() {
        URI lra = lraClient.startLRA("test-renew-timeout");
        lrasToAfterFinish.add(lra);

        Response renewResponse = lraClient.renewTimeLimit(lra, 60000L);
        Assert.assertNotNull("Renew response should not be null", renewResponse);
        Assert.assertTrue("Renew response should be successful",
                renewResponse.getStatus() == Response.Status.OK.getStatusCode() ||
                        renewResponse.getStatus() == Response.Status.NO_CONTENT.getStatusCode());
    }

    @Test
    public void testJoinLRAWithCompensatorEndpoints() {
        URI lra = lraClient.startLRA("test-join-lra");
        lrasToAfterFinish.add(lra);

        URI compensateUri = URI.create("http://localhost:8080/compensate");
        URI completeUri = URI.create("http://localhost:8080/complete");
        URI statusUri = URI.create("http://localhost:8080/status");

        URI recoveryUri = lraClient.joinLRA(lra, 30000L,
                compensateUri, completeUri, null, null, null, statusUri, "test-data");

        Assert.assertNotNull("Recovery URI should be returned", recoveryUri);
    }

    @Test
    public void testJoinLRAWithParticipantUri() {
        URI lra = lraClient.startLRA("test-join-participant");
        lrasToAfterFinish.add(lra);

        URI participantUri = URI.create("http://localhost:8080/participant");
        StringBuilder compensatorData = new StringBuilder("test-data");

        URI recoveryUri = lraClient.joinLRA(lra, 30000L, participantUri, compensatorData);

        Assert.assertNotNull("Recovery URI should be returned", recoveryUri);
    }

    @Test
    public void testLeaveLRA() {
        URI lra = lraClient.startLRA("test-leave-lra");
        lrasToAfterFinish.add(lra);

        // Join first
        URI participantUri = URI.create("http://localhost:8080/participant");
        URI recoveryURI = lraClient.joinLRA(lra, 30000L, participantUri, new StringBuilder());

        // Then leave
        lraClient.leaveLRA(lra, recoveryURI.toString());

        // LRA should still be active after participant leaves
        LRAStatus status = lraClient.getStatus(lra);
        Assert.assertEquals("LRA should still be active after participant leaves", LRAStatus.Active, status);
    }

    @Test
    public void testClose() {
        System.setProperty(NarayanaLRAClient.LRA_COORDINATOR_URL_KEY,
                "http://localhost:8080/lra-coordinator,http://localhost:8081/lra-coordinator");
        NarayanaLRAClient testClient = new NarayanaLRAClient();

        // Test that close doesn't throw an exception
        testClient.close();

        // Method should be idempotent
        testClient.close();
    }

}
