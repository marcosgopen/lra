/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian.internal;

import io.narayana.lra.logging.LRALogger;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Utility for waiting on LRA status signals produced by a Byteman rule
 * running in the coordinator JVM.
 *
 * <p>
 * Please use a byteman rule file to append (e.g. lra-status-signal.btm)
 * {@code <lraId>=<status>} lines to a shared signal file when an LRA
 * reaches a terminal status. This class polls that file so tests can
 * replace fragile {@code Thread.sleep} loops with a deterministic wait.
 * </p>
 */
public class BytemanSignal {

    private static final long POLL_INTERVAL_MS = 200;

    private final Path signalFile;

    public BytemanSignal() {
        String signalDir = System.getProperty("byteman.signal.dir",
                System.getProperty("project.build.directory", "/tmp") + "/byteman-signals");
        this.signalFile = Path.of(signalDir, "lra-signals.log");
    }

    /**
     * Clear the signal file before a test so stale entries from previous
     * runs are not picked up.
     */
    public void clear() {
        try {
            Files.createDirectories(signalFile.getParent());
            Files.deleteIfExists(signalFile);
        } catch (IOException e) {
            LRALogger.logger.debugf("Could not clear signal file %s: %s", signalFile, e.getMessage());
        }
    }

    /**
     * Wait for the Byteman rule to signal that the given LRA reached the
     * expected status.
     *
     * @param lraId the LRA to wait for
     * @param expectedStatus the status name (e.g. "Cancelled")
     * @param timeoutSeconds maximum seconds to wait
     * @return true if the signal was found within the timeout
     */
    public boolean waitFor(URI lraId, String expectedStatus, long timeoutSeconds) {
        String expected = lraId.toASCIIString() + "=" + expectedStatus;
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000;

        LRALogger.logger.infof("Waiting for byteman signal: %s in %s", expected, signalFile);

        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(signalFile)) {
                try {
                    String content = Files.readString(signalFile, StandardCharsets.UTF_8);
                    if (content.contains(expected)) {
                        LRALogger.logger.infof("Byteman signal received: %s", expected);
                        return true;
                    }
                } catch (IOException e) {
                    LRALogger.logger.debugf("Could not read signal file: %s", e.getMessage());
                }
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        LRALogger.logger.warnf("Timed out waiting for byteman signal: %s", expected);
        return false;
    }
}
