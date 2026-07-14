/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.internal;

import com.arjuna.ats.arjuna.exceptions.ObjectStoreException;

/**
 * Thrown when the distributed object store is temporarily unavailable —
 * for example, because the Infinispan cluster has entered a degraded
 * partition or the cache manager has not yet started.
 *
 * <p>
 * Callers that catch {@link ObjectStoreException} and want to distinguish
 * a transient availability failure (HTTP 503) from a hard store error
 * (HTTP 500) should catch this subclass first:
 * </p>
 *
 * <pre>{@code
 * try {
 *     store.write_committed(uid, type, state);
 * } catch (StoreUnavailableException e) {
 *     // cluster not available – tell the client to retry
 *     throw new WebApplicationException(Response.Status.SERVICE_UNAVAILABLE);
 * } catch (ObjectStoreException e) {
 *     // hard failure
 *     throw new WebApplicationException(Response.Status.INTERNAL_SERVER_ERROR);
 * }
 * }</pre>
 *
 * <p>
 * This class lives in the {@code coordinator} module so that any component
 * that depends on {@code coordinator} can catch it without adding a
 * compile-time dependency on {@code coordinator-ha-infinispan}.
 * </p>
 */
public class StoreUnavailableException extends ObjectStoreException {

    public StoreUnavailableException(String message, Throwable cause) {
        // Pass only the primary message to super(); the JVM stack trace already
        // prints the chained cause, so embedding cause.getMessage() here would
        // duplicate it and risk a NullPointerException when cause has no message.
        super(message);
        if (cause != null) {
            initCause(cause);
        }
    }
}
