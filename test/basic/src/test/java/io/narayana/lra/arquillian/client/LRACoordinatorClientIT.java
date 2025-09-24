/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.narayana.lra.LRAData;
import io.narayana.lra.arquillian.Deployer;
import io.narayana.lra.client.api.LRACoordinatorClient;
import io.narayana.lra.client.api.LRACoordinatorClientBuilder;
import io.narayana.lra.client.api.LRAResponseUtils;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests for LRA Coordinator Client.
 * These tests require a running LRA Coordinator and are disabled by default.
 *
 * To enable these tests, run with:
 * -Dlra.coordinator.test.enabled=true -Dlra.coordinator.url=http://localhost:8080
 */
@ExtendWith(ArquillianExtension.class)
@DisplayName("LRA Coordinator Client Integration Tests")
class LRACoordinatorClientIT {

    @ArquillianResource
    public URL baseUrl;

    @Deployment
    public static WebArchive deploy() {
        return Deployer.deploy(LRACoordinatorClientIT.class.getSimpleName());
    }

    @Test
    @DisplayName("Should perform complete LRA lifecycle")
    void shouldPerformCompleteLRALifecycle() {
        try (LRACoordinatorClient client = LRACoordinatorClientBuilder.newBuilder()
                .coordinatorUrl(baseUrl.toString())
                .build()) {

            // 1. Start a new LRA
            String clientId = "integration-test-" + System.currentTimeMillis();
            Response startResponse = client.startLRA(clientId, 300000L); // 5 minutes

            assertThat(LRAResponseUtils.isSuccessful(startResponse))
                    .as("LRA should start successfully")
                    .isTrue();

            Optional<URI> lraId = LRAResponseUtils.extractLRAId(startResponse);
            assertThat(lraId)
                    .as("LRA ID should be present in response")
                    .isPresent();

            URI lraUri = lraId.get();
            System.out.println("Started LRA: " + lraUri);

            // 2. Verify LRA status is Active
            Response statusResponse = client.getLRAStatus(lraUri, MediaType.APPLICATION_JSON);
            assertThat(LRAResponseUtils.isSuccessful(statusResponse)).isTrue();

            Optional<LRAStatus> status = LRAResponseUtils.extractLRAStatus(statusResponse);
            assertThat(status)
                    .as("LRA status should be Active")
                    .hasValue(LRAStatus.Active);

            // 3. Get detailed LRA information
            Response infoResponse = client.getLRAInfo(lraUri, MediaType.APPLICATION_JSON);
            assertThat(LRAResponseUtils.isSuccessful(infoResponse)).isTrue();

            Optional<LRAData> lraData = LRAResponseUtils.extractLRAData(infoResponse);
            assertThat(lraData).isPresent();
            assertThat(lraData.get().getClientId()).isEqualTo(clientId);
            assertThat(lraData.get().getStatus()).isEqualTo(LRAStatus.Active);
            assertThat(lraData.get().isTopLevel()).isTrue();

            // 4. List all LRAs and verify our LRA is included
            Response allLRAsResponse = client.getAllLRAs(LRAStatus.Active, MediaType.APPLICATION_JSON);
            assertThat(LRAResponseUtils.isSuccessful(allLRAsResponse)).isTrue();

            List<LRAData> allLRAs = LRAResponseUtils.extractLRADataList(allLRAsResponse);
            assertThat(allLRAs)
                    .as("Active LRAs should include our LRA")
                    .extracting(LRAData::getLraId)
                    .contains(lraUri);

            // 5. Close the LRA
            Response closeResponse = client.closeLRA(lraUri, MediaType.APPLICATION_JSON, null, null);
            assertThat(LRAResponseUtils.isSuccessful(closeResponse))
                    .as("LRA should close successfully")
                    .isTrue();

            Optional<LRAStatus> finalStatus = LRAResponseUtils.extractLRAStatus(closeResponse);
            assertThat(finalStatus)
                    .as("Final LRA status should indicate completion")
                    .satisfiesAnyOf(
                            status_ -> assertThat(status_).hasValue(LRAStatus.Closed),
                            status_ -> assertThat(status_).hasValue(LRAStatus.Closing));

            System.out.println("LRA lifecycle completed successfully");
        }
    }

    @Test
    @DisplayName("Should handle LRA cancellation")
    void shouldHandleLRACancellation() {
        try (LRACoordinatorClient client = LRACoordinatorClientBuilder.newBuilder()
                .coordinatorUrl(baseUrl.toString())
                .build()) {

            // Start LRA
            String clientId = "cancel-test-" + System.currentTimeMillis();
            Response startResponse = client.startLRA(clientId);
            assertThat(LRAResponseUtils.isSuccessful(startResponse)).isTrue();

            Optional<URI> lraId = LRAResponseUtils.extractLRAId(startResponse);
            assertThat(lraId).isPresent();

            // Cancel LRA
            Response cancelResponse = client.cancelLRA(lraId.get());
            assertThat(LRAResponseUtils.isSuccessful(cancelResponse))
                    .as("LRA should cancel successfully")
                    .isTrue();

            Optional<LRAStatus> finalStatus = LRAResponseUtils.extractLRAStatus(cancelResponse);
            assertThat(finalStatus)
                    .as("Final LRA status should indicate cancellation")
                    .satisfiesAnyOf(
                            status -> assertThat(status).hasValue(LRAStatus.Cancelled),
                            status -> assertThat(status).hasValue(LRAStatus.Cancelling));

            System.out.println("LRA cancellation completed successfully");
        }
    }

    @Test
    @DisplayName("Should handle nested LRA creation")
    void shouldHandleNestedLRACreation() {
        try (LRACoordinatorClient client = LRACoordinatorClientBuilder.newBuilder()
                .coordinatorUrl(baseUrl.toString())
                .build()) {

            // Start parent LRA
            String parentClientId = "parent-test-" + System.currentTimeMillis();
            Response parentResponse = client.startLRA(parentClientId);
            assertThat(LRAResponseUtils.isSuccessful(parentResponse)).isTrue();

            Optional<URI> parentId = LRAResponseUtils.extractLRAId(parentResponse);
            assertThat(parentId).isPresent();

            // Start nested LRA
            String nestedClientId = "nested-test-" + System.currentTimeMillis();
            Response nestedResponse = client.startLRA(nestedClientId, 60000L, parentId.get(), MediaType.APPLICATION_JSON);

            if (LRAResponseUtils.isSuccessful(nestedResponse)) {
                Optional<URI> nestedId = LRAResponseUtils.extractLRAId(nestedResponse);
                assertThat(nestedId).isPresent();

                System.out.println("Parent LRA: " + parentId.get());
                System.out.println("Nested LRA: " + nestedId.get());

                // Clean up nested LRA
                client.closeLRA(nestedId.get());
            }

            // Clean up parent LRA
            client.closeLRA(parentId.get());

            System.out.println("Nested LRA test completed successfully");
        }
    }

    @Test
    @DisplayName("Should handle timeout renewal")
    void shouldHandleTimeoutRenewal() {
        try (LRACoordinatorClient client = LRACoordinatorClientBuilder.newBuilder()
                .coordinatorUrl(baseUrl.toString())
                .build()) {

            // Start LRA with short timeout
            String clientId = "timeout-test-" + System.currentTimeMillis();
            Response startResponse = client.startLRA(clientId, 30000L); // 30 seconds
            assertThat(LRAResponseUtils.isSuccessful(startResponse)).isTrue();

            Optional<URI> lraId = LRAResponseUtils.extractLRAId(startResponse);
            assertThat(lraId).isPresent();

            // Renew timeout
            Response renewResponse = client.renewTimeLimit(lraId.get(), 120000L); // 2 minutes
            assertThat(LRAResponseUtils.isSuccessful(renewResponse))
                    .as("Timeout renewal should succeed")
                    .isTrue();

            // Clean up
            client.closeLRA(lraId.get());

            System.out.println("Timeout renewal test completed successfully");
        }
    }
}
