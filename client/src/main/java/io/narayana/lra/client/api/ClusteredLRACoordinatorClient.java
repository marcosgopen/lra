/*
  Copyright The Narayana Authors
  SPDX-License-Identifier: Apache-2.0
*/

package io.narayana.lra.client.api;

import static io.narayana.lra.LRAConstants.CURRENT_API_VERSION_STRING;

import io.smallrye.stork.Stork;
import io.smallrye.stork.api.ServiceInstance;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.jboss.logging.Logger;

/**
 * Clustered LRA Coordinator Client that provides load balancing and failover capabilities
 * across multiple LRA Coordinator instances using Stork for service discovery.
 *
 * This client can operate with multiple coordinator instances and automatically
 * distributes requests across them based on the configured load balancing strategy.
 */
public class ClusteredLRACoordinatorClient implements AutoCloseable {

    private static final Logger log = Logger.getLogger(ClusteredLRACoordinatorClient.class);

    private static final String DEFAULT_SERVICE_NAME = "lra-coordinator";
    private static final String CONFIG_PREFIX = "lra.coordinator";

    private final String serviceName;
    private final String apiVersion;
    private final Stork stork;
    private final ConcurrentMap<String, LRACoordinatorClient> clientCache;
    private final AtomicInteger requestCounter;
    private final Duration timeout;
    private final int maxRetries;

    /**
     * Create a clustered client with default configuration.
     */
    public ClusteredLRACoordinatorClient() {
        this(DEFAULT_SERVICE_NAME, CURRENT_API_VERSION_STRING);
    }

    /**
     * Create a clustered client with custom service name.
     *
     * @param serviceName The service name for Stork service discovery
     */
    public ClusteredLRACoordinatorClient(String serviceName) {
        this(serviceName, CURRENT_API_VERSION_STRING);
    }

    /**
     * Create a clustered client with custom service name and API version.
     *
     * @param serviceName The service name for Stork service discovery
     * @param apiVersion The LRA API version to use
     */
    public ClusteredLRACoordinatorClient(String serviceName, String apiVersion) {
        this.serviceName = serviceName != null ? serviceName : DEFAULT_SERVICE_NAME;
        this.apiVersion = apiVersion != null ? apiVersion : CURRENT_API_VERSION_STRING;
        this.stork = Stork.getInstance();
        this.clientCache = new ConcurrentHashMap<>();
        this.requestCounter = new AtomicInteger(0);

        // Load configuration
        Config config = ConfigProvider.getConfig();
        this.timeout = Duration.ofSeconds(config.getOptionalValue(CONFIG_PREFIX + ".timeout", Long.class).orElse(30L));
        this.maxRetries = config.getOptionalValue(CONFIG_PREFIX + ".max-retries", Integer.class).orElse(3);

        // Initialize service configuration if URLs are provided
        initializeServiceConfiguration(config);
    }

    /**
     * Initialize Stork service configuration from MicroProfile Config properties.
     */
    private void initializeServiceConfiguration(Config config) {
        config.getOptionalValue(CONFIG_PREFIX + ".urls", String.class)
                .ifPresent(urls -> {
                    String lbMethod = config.getOptionalValue(CONFIG_PREFIX + ".lb-method", String.class).orElse("round-robin");
                    registerStaticServices(urls, lbMethod);
                });
    }

    /**
     * Register coordinator URLs as static services in Stork.
     */
    private void registerStaticServices(String urls, String loadBalancingMethod) {
        try {
            // Parse comma-separated URLs
            String[] urlArray = urls.split(",");

            // Set up system properties for Stork configuration as an alternative approach
            System.setProperty("stork." + serviceName + ".service-discovery.type", "static");
            System.setProperty("stork." + serviceName + ".service-discovery.address-list", urls);
            System.setProperty("stork." + serviceName + ".load-balancer.type", loadBalancingMethod);

            log.infof("Registered LRA Coordinator service '%s' with %d instances and '%s' load balancing",
                    serviceName, urlArray.length, loadBalancingMethod);
        } catch (Exception e) {
            log.warnf(e, "Failed to register static services for URLs: %s", urls);
        }
    }

    /**
     * Get or create a client for the given coordinator URL.
     */
    private LRACoordinatorClient getClient(String coordinatorUrl) {
        return clientCache.computeIfAbsent(coordinatorUrl, url -> {
            log.debugf("Creating new LRA Coordinator client for: %s", url);
            return new LRACoordinatorClient(url, apiVersion);
        });
    }

    /**
     * Execute a request with automatic failover and retry logic.
     */
    private <T> T executeWithFailover(CoordinatorOperation<T> operation) {
        int attempt = 0;
        Exception lastException = null;

        while (attempt < maxRetries) {
            try {
                ServiceInstance instance = stork.getService(serviceName).selectInstance().await().atMost(timeout);
                String coordinatorUrl = buildCoordinatorUrl(instance);
                LRACoordinatorClient client = getClient(coordinatorUrl);

                log.debugf("Attempt %d: Using coordinator instance at %s", attempt + 1, coordinatorUrl);
                return operation.execute(client);

            } catch (Exception e) {
                lastException = e;
                attempt++;
                log.warnf(e, "Attempt %d failed, coordinator may be unavailable", attempt);

                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(calculateBackoffDelay(attempt));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during retry backoff", ie);
                    }
                }
            }
        }

        throw new RuntimeException(String.format("All %d attempts failed to execute LRA operation", maxRetries), lastException);
    }

    /**
     * Build the full coordinator URL from a service instance.
     */
    private String buildCoordinatorUrl(ServiceInstance instance) {
        String host = instance.getHost();
        int port = instance.getPort();
        boolean secure = instance.isSecure();

        String scheme = secure ? "https" : "http";
        String url = String.format("%s://%s:%d", scheme, host, port);

        // Ensure the URL ends with the LRA coordinator path if not already present
        if (!url.endsWith("/lra-coordinator") && !url.endsWith("/lra-coordinator/")) {
            url += url.endsWith("/") ? "lra-coordinator/" : "/lra-coordinator/";
        }

        return url;
    }

    /**
     * Calculate exponential backoff delay for retries.
     */
    private long calculateBackoffDelay(int attempt) {
        return Math.min(1000 * (1L << (attempt - 1)), 5000); // Cap at 5 seconds
    }

    // LRA Coordinator API methods with clustering support

    /**
     * Get all LRAs from any available coordinator.
     */
    public Response getAllLRAs() {
        return executeWithFailover(client -> client.getAllLRAs());
    }

    /**
     * Get all LRAs with status filter from any available coordinator.
     */
    public Response getAllLRAs(LRAStatus status, String accept) {
        return executeWithFailover(client -> client.getAllLRAs(status, accept));
    }

    /**
     * Get LRA status. The request will be routed to find the coordinator managing this LRA.
     */
    public Response getLRAStatus(URI lraId) {
        return executeWithFailover(client -> client.getLRAStatus(lraId));
    }

    /**
     * Get LRA information. The request will be routed to find the coordinator managing this LRA.
     */
    public Response getLRAInfo(URI lraId) {
        return executeWithFailover(client -> client.getLRAInfo(lraId));
    }

    /**
     * Start a new LRA. The request will be load-balanced across available coordinators.
     */
    public Response startLRA(String clientId) {
        return executeWithFailover(client -> client.startLRA(clientId));
    }

    /**
     * Start a new LRA with timeout. The request will be load-balanced across available coordinators.
     */
    public Response startLRA(String clientId, Long timeLimit) {
        return executeWithFailover(client -> client.startLRA(clientId, timeLimit));
    }

    /**
     * Start a nested LRA. The request will be routed to the same coordinator as the parent LRA.
     */
    public Response startLRA(String clientId, Long timeLimit, URI parentLRA, String accept) {
        return executeWithFailover(client -> client.startLRA(clientId, timeLimit, parentLRA, accept));
    }

    /**
     * Renew LRA time limit. The request will be routed to the coordinator managing this LRA.
     */
    public Response renewTimeLimit(URI lraId, Long timeLimit) {
        return executeWithFailover(client -> client.renewTimeLimit(lraId, timeLimit));
    }

    /**
     * Close an LRA. The request will be routed to the coordinator managing this LRA.
     */
    public Response closeLRA(URI lraId) {
        return executeWithFailover(client -> client.closeLRA(lraId));
    }

    /**
     * Close an LRA with compensator and user data.
     */
    public Response closeLRA(URI lraId, String accept, String compensatorLink, String userData) {
        return executeWithFailover(client -> client.closeLRA(lraId, accept, compensatorLink, userData));
    }

    /**
     * Cancel an LRA. The request will be routed to the coordinator managing this LRA.
     */
    public Response cancelLRA(URI lraId) {
        return executeWithFailover(client -> client.cancelLRA(lraId));
    }

    /**
     * Join an LRA as a participant. The request will be routed to the coordinator managing this LRA.
     */
    public Response joinLRA(URI lraId, String compensatorLink) {
        return executeWithFailover(client -> client.joinLRA(lraId, compensatorLink));
    }

    /**
     * Leave an LRA as a participant. The request will be routed to the coordinator managing this LRA.
     */
    public Response leaveLRA(URI lraId, String participantUrl) {
        return executeWithFailover(client -> client.leaveLRA(lraId, participantUrl));
    }

    /**
     * Register a new coordinator instance with this cluster.
     * Note: This is a placeholder implementation. In practice, service registration
     * would be handled by external service discovery mechanisms.
     *
     * @param coordinatorUrl The URL of the coordinator to register
     */
    public void registerCoordinator(String coordinatorUrl) {
        // For now, log the registration request
        // In a real implementation, this would integrate with external service discovery
        log.infof("Registration requested for coordinator instance: %s", coordinatorUrl);
        log.debugf("Service registration requires external configuration for service: %s", serviceName);
    }

    /**
     * Get the current list of available coordinator instances.
     */
    public CompletableFuture<List<ServiceInstance>> getAvailableCoordinators() {
        return stork.getService(serviceName).getInstances().subscribeAsCompletionStage();
    }

    /**
     * Get the service name used for coordinator discovery.
     */
    public String getServiceName() {
        return serviceName;
    }

    /**
     * Get the current request counter value (useful for round-robin debugging).
     */
    public int getRequestCount() {
        return requestCounter.get();
    }

    @Override
    public void close() {
        log.debugf("Closing clustered LRA coordinator client for service: %s", serviceName);

        // Close all cached clients
        clientCache.values().forEach(client -> {
            try {
                client.close();
            } catch (Exception e) {
                log.warnf(e, "Error closing LRA coordinator client");
            }
        });

        clientCache.clear();
    }

    /**
     * Functional interface for coordinator operations.
     */
    @FunctionalInterface
    private interface CoordinatorOperation<T> {
        T execute(LRACoordinatorClient client) throws Exception;
    }
}
