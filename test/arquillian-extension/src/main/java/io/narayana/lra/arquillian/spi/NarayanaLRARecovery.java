/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian.spi;

import static io.narayana.lra.LRAConstants.RECOVERY_COORDINATOR_PATH_NAME;

import java.net.URI;

import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.eclipse.microprofile.lra.tck.service.spi.LRARecoveryService;
import org.jboss.logging.Logger;

import io.narayana.lra.LRAConstants;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;

public class NarayanaLRARecovery implements LRARecoveryService {
    private static final Logger log = Logger.getLogger(NarayanaLRARecovery.class);

    /*
     * invoke the LRAParticipant.status(URI lraId, URI parentId) if that returns 200
     * then ok if that returns 202 then repeat
     * 
     */
    @Override
    public void waitForCallbacks(URI lraId) {
        int counter = 0;
        Response response;
        String status;
        do {
            log.info("Checking if the CompletionStage has finished, attempt #" + ++counter);
            log.info("waitForCallbacks for: " + lraId.toASCIIString());
            try (Client client = ClientBuilder.newClient()) {
                WebTarget coordinatorTarget = client.target(UriBuilder.fromUri(lraId).path("status").build());
                response = coordinatorTarget.request().get();
                status = response.readEntity(String.class);
                response.close();
            }
        }
        while (LRAStatus.Cancelling.name().equals(status) || LRAStatus.Closing.name().equals(status));
        log.info("LRA " + lraId + "has received the callback");
    }

    @Override
    public boolean waitForEndPhaseReplay(URI lraId) {
        log.info("waitForEndPhaseReplay for: " + lraId.toASCIIString());
        if (!recoverLRAs(lraId)) {
            // first recovery scan probably collided with periodic recovery which started
            // before the test execution so try once more
            return recoverLRAs(lraId);
        }
        return true;
    }

    /**
     * Invokes LRA coordinator recovery REST endpoint and returns whether the recovery of intended LRAs happened
     *
     * @param lraId the LRA id of the LRA that is intended to be recovered
     * @return true the intended LRA recovered, false otherwise
     */
    private boolean recoverLRAs(URI lraId) {
        // trigger a recovery scan

        try (Client recoveryCoordinatorClient = ClientBuilder.newClient()) {
            URI lraCoordinatorUri = LRAConstants.getLRACoordinatorUrl(lraId);
            URI recoveryCoordinatorUri = UriBuilder.fromUri(lraCoordinatorUri)
                    .path(RECOVERY_COORDINATOR_PATH_NAME).build();
            WebTarget recoveryTarget = recoveryCoordinatorClient.target(recoveryCoordinatorUri);

            // send the request to the recovery coordinator
            Response response = recoveryTarget.request().get();
            String json = response.readEntity(String.class);
            response.close();

            // intended LRA didn't recover
            return !json.contains(lraId.toASCIIString());
        }
    }
}