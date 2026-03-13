/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */
package io.narayana.lra.coordinator.domain.model;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Byteman helper used in two ways:
 * <ul>
 * <li>As a {@code helper} class in {@code @BMRule} actions (e.g. {@link #abortLRA})</li>
 * <li>As a static rendezvous utility so tests can wait for coordinator events
 * (e.g. an LRA reaching a transient or terminal status) without using
 * {@code Thread.sleep}</li>
 * </ul>
 */
public class BytemanHelper {

    private static final ConcurrentHashMap<String, CountDownLatch> latches = new ConcurrentHashMap<>();

    /**
     * Create a rendezvous point. Call from the test thread before the event.
     */
    public static void createRendezvous(String name) {
        latches.put(name, new CountDownLatch(1));
    }

    /**
     * Signal that the named event occurred. Called from a {@code @BMRule} action.
     */
    public static void signalRendezvous(String name) {
        CountDownLatch latch = latches.get(name);
        if (latch != null) {
            latch.countDown();
        }
    }

    /**
     * Wait for the named event. Call from the test thread after triggering the action.
     *
     * @return true if the event was signalled within the timeout
     */
    public static boolean awaitRendezvous(String name, long timeoutSeconds) throws InterruptedException {
        CountDownLatch latch = latches.get(name);
        if (latch == null) {
            return true;
        }
        return latch.await(timeoutSeconds, TimeUnit.SECONDS);
    }

    /**
     * Wait for the named event without throwing checked exceptions.
     * Intended for use in {@code @BMRule} actions where checked exceptions
     * are not permitted.
     *
     * @return true if the event was signalled within the timeout
     */
    public static boolean awaitRendezvousBM(String name, long timeoutSeconds) {
        CountDownLatch latch = latches.get(name);
        if (latch == null) {
            return true;
        }
        try {
            return latch.await(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Remove the named rendezvous point (cleanup).
     */
    public static void removeRendezvous(String name) {
        latches.remove(name);
    }

    public void abortLRA(LongRunningAction lra) throws NoSuchMethodException, InvocationTargetException,
            IllegalAccessException {
        Method method = lra.getClass().getDeclaredMethod("abortLRA");
        method.setAccessible(true);
        method.invoke(lra);
    }
}
