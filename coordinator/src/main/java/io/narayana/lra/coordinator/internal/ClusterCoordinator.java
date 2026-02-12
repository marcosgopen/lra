/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.internal;

import io.narayana.lra.logging.LRALogger;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.remoting.transport.Address;
import org.jgroups.JChannel;
import org.jgroups.View;
import org.jgroups.ViewListener;

/**
 * Manages cluster coordination and leader election for LRA coordinators.
 *
 * Uses JGroups coordinator election (first member in view becomes coordinator).
 * This is simpler than Raft and sufficient for LRA recovery coordination.
 *
 * In HA mode, only the cluster coordinator performs recovery operations.
 * This prevents multiple coordinators from trying to recover the same LRA
 * simultaneously.
 */
@ApplicationScoped
public class ClusterCoordinator implements ViewListener {

    private EmbeddedCacheManager cacheManager;
    private JChannel channel;
    private boolean initialized = false;
    private volatile boolean isCoordinator = false;
    private final List<CoordinatorChangeListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Initializes cluster coordination using the Infinispan cache manager.
     * The cache manager already has JGroups configured.
     *
     * @param cacheManager the Infinispan cache manager
     */
    public void initialize(EmbeddedCacheManager cacheManager) {
        if (initialized || cacheManager == null) {
            return;
        }

        try {
            this.cacheManager = cacheManager;

            // Get JChannel from Infinispan's transport
            org.infinispan.remoting.transport.Transport transport = cacheManager.getTransport();

            if (transport == null) {
                LRALogger.logger.warn("Infinispan transport not available, running in local mode");
                return;
            }

            // Get JGroups channel from Infinispan transport
            this.channel = (JChannel) transport.getChannel();

            // Add view listener to detect coordinator changes
            channel.addViewListener(this);

            // Check initial coordinator status
            updateCoordinatorStatus(channel.getView());

            initialized = true;
            LRALogger.logger.infof("ClusterCoordinator initialized (isCoordinator=%s)", isCoordinator);

        } catch (Exception e) {
            LRALogger.logger.warnf(e, "Failed to initialize ClusterCoordinator");
        }
    }

    /**
     * Alternative initialization for environments where we need to create
     * a standalone JGroups channel.
     */
    @PostConstruct
    public void initializeStandalone() {
        try {
            // Check if HA mode is enabled
            String haEnabled = System.getProperty("lra.coordinator.ha.enabled", "false");
            if (!"true".equalsIgnoreCase(haEnabled)) {
                LRALogger.logger.debug("LRA HA mode disabled, ClusterCoordinator will not be initialized");
                return;
            }

            // If we already have a cache manager (from CDI), we're done
            if (cacheManager != null) {
                return;
            }

            LRALogger.logger.info("Initializing standalone ClusterCoordinator");

            // Get cluster configuration
            String clusterName = System.getProperty("lra.coordinator.cluster.name",
                    System.getenv().getOrDefault("LRA_CLUSTER_NAME", "lra-cluster"));
            String nodeName = getNodeName();

            // Create JGroups channel with default JGroups config
            String configFile = System.getProperty("lra.coordinator.jgroups.config",
                    "default-jgroups-udp.xml");

            channel = new JChannel(configFile);
            channel.setName(nodeName);
            channel.addViewListener(this);
            channel.connect(clusterName);

            // Check initial coordinator status
            updateCoordinatorStatus(channel.getView());

            initialized = true;
            LRALogger.logger.infof("Standalone ClusterCoordinator initialized for cluster '%s' (isCoordinator=%s)",
                    clusterName, isCoordinator);

        } catch (Exception e) {
            LRALogger.logger.warnf(e, "Failed to initialize standalone ClusterCoordinator, running in single-instance mode");
        }
    }

    /**
     * JGroups ViewListener callback - called when cluster membership changes.
     */
    @Override
    public void viewAccepted(View view) {
        LRALogger.logger.infof("New cluster view: %s", view);
        updateCoordinatorStatus(view);
    }

    /**
     * Updates coordinator status based on the JGroups view.
     * The first member in the view is the coordinator.
     */
    private void updateCoordinatorStatus(View view) {
        if (view == null || channel == null) {
            return;
        }

        boolean wasCoordinator = isCoordinator;
        Address localAddress = channel.getAddress();
        Address coordinator = view.getCoord();

        isCoordinator = localAddress.equals(coordinator);

        LRALogger.logger.infof("Coordinator status: %s (local=%s, coordinator=%s)",
                isCoordinator ? "COORDINATOR" : "FOLLOWER", localAddress, coordinator);

        // Notify listeners of coordinator change
        if (isCoordinator && !wasCoordinator) {
            notifyBecameCoordinator();
        } else if (!isCoordinator && wasCoordinator) {
            notifyLostCoordinator();
        }
    }

    /**
     * Adds a coordinator change listener.
     *
     * @param listener the listener
     */
    public void addCoordinatorChangeListener(CoordinatorChangeListener listener) {
        listeners.add(listener);
    }

    /**
     * Removes a coordinator change listener.
     *
     * @param listener the listener
     */
    public void removeCoordinatorChangeListener(CoordinatorChangeListener listener) {
        listeners.remove(listener);
    }

    /**
     * Notifies listeners that this node became the coordinator.
     */
    private void notifyBecameCoordinator() {
        for (CoordinatorChangeListener listener : listeners) {
            try {
                listener.onBecameCoordinator();
            } catch (Exception e) {
                LRALogger.logger.warnf(e, "Error notifying coordinator change listener");
            }
        }
    }

    /**
     * Notifies listeners that this node lost coordinator status.
     */
    private void notifyLostCoordinator() {
        for (CoordinatorChangeListener listener : listeners) {
            try {
                listener.onLostCoordinator();
            } catch (Exception e) {
                LRALogger.logger.warnf(e, "Error notifying coordinator change listener");
            }
        }
    }

    /**
     * Checks if this node is the cluster coordinator.
     *
     * @return true if this node is the coordinator
     */
    public boolean isCoordinator() {
        return isCoordinator;
    }

    /**
     * Checks if cluster coordination is initialized.
     *
     * @return true if initialized
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Gets the node name for this coordinator instance.
     *
     * @return the node name
     */
    private String getNodeName() {
        String nodeName = System.getProperty("lra.coordinator.node.id");
        if (nodeName == null || nodeName.isEmpty()) {
            nodeName = System.getenv("HOSTNAME");
        }
        if (nodeName == null || nodeName.isEmpty()) {
            nodeName = "lra-coordinator-" + System.currentTimeMillis();
        }
        return nodeName;
    }

    /**
     * Shuts down cluster coordination.
     */
    @PreDestroy
    public void shutdown() {
        if (channel != null && channel.isConnected()) {
            LRALogger.logger.info("Shutting down ClusterCoordinator");
            channel.close();
        }
    }

    /**
     * Listener for coordinator changes.
     */
    public interface CoordinatorChangeListener {
        /**
         * Called when this node becomes the cluster coordinator.
         */
        void onBecameCoordinator();

        /**
         * Called when this node loses cluster coordinator status.
         */
        void onLostCoordinator();
    }
}
