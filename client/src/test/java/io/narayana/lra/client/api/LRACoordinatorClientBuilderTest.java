/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LRA Coordinator Client Builder Tests")
class LRACoordinatorClientBuilderTest {

    private static final String TEST_COORDINATOR_URL = "http://localhost:8080";

    @Test
    @DisplayName("Should create builder instance")
    void shouldCreateBuilderInstance() {
        LRACoordinatorClientBuilder builder = LRACoordinatorClientBuilder.newBuilder();
        assertThat(builder).isNotNull();
    }

    @Test
    @DisplayName("Should build client with coordinator URL")
    void shouldBuildClientWithCoordinatorUrl() {
        try (LRACoordinatorClient client = LRACoordinatorClientBuilder.newBuilder()
                .coordinatorUrl(TEST_COORDINATOR_URL)
                .build()) {

            assertThat(client).isNotNull();
        }
    }

    @Test
    @DisplayName("Should build client with custom API version")
    void shouldBuildClientWithCustomApiVersion() {
        String customVersion = "2.0";

        try (LRACoordinatorClient client = LRACoordinatorClientBuilder.newBuilder()
                .coordinatorUrl(TEST_COORDINATOR_URL)
                .apiVersion(customVersion)
                .build()) {

            assertThat(client).isNotNull();
        }
    }

    @Test
    @DisplayName("Should build client with custom timeouts")
    void shouldBuildClientWithCustomTimeouts() {
        try (LRACoordinatorClient client = LRACoordinatorClientBuilder.newBuilder()
                .coordinatorUrl(TEST_COORDINATOR_URL)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()) {

            assertThat(client).isNotNull();
        }
    }

    @Test
    @DisplayName("Should throw exception when coordinator URL is null")
    void shouldThrowExceptionWhenCoordinatorUrlIsNull() {
        assertThatThrownBy(() -> LRACoordinatorClientBuilder.newBuilder()
                .coordinatorUrl(null)
                .build()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Coordinator base URL must be specified");
    }

    @Test
    @DisplayName("Should throw exception when coordinator URL is empty")
    void shouldThrowExceptionWhenCoordinatorUrlIsEmpty() {
        assertThatThrownBy(() -> LRACoordinatorClientBuilder.newBuilder()
                .coordinatorUrl("")
                .build()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Coordinator base URL must be specified");
    }

    @Test
    @DisplayName("Should throw exception when coordinator URL is blank")
    void shouldThrowExceptionWhenCoordinatorUrlIsBlank() {
        assertThatThrownBy(() -> LRACoordinatorClientBuilder.newBuilder()
                .coordinatorUrl("   ")
                .build()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Coordinator base URL must be specified");
    }

    @Test
    @DisplayName("Should throw exception when no coordinator URL is set")
    void shouldThrowExceptionWhenNoCoordinatorUrlIsSet() {
        assertThatThrownBy(() -> LRACoordinatorClientBuilder.newBuilder()
                .apiVersion("1.0")
                .build()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Coordinator base URL must be specified");
    }

    @Test
    @DisplayName("Should allow method chaining")
    void shouldAllowMethodChaining() {
        LRACoordinatorClientBuilder builder = LRACoordinatorClientBuilder.newBuilder();

        LRACoordinatorClientBuilder result = builder
                .coordinatorUrl(TEST_COORDINATOR_URL)
                .apiVersion("1.0")
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS);

        assertThat(result).isSameAs(builder);

        try (LRACoordinatorClient client = result.build()) {
            assertThat(client).isNotNull();
        }
    }
}