/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.security;

import jakarta.enterprise.context.RequestScoped;
import java.util.Set;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Minimal {@link RequestScoped} {@link JsonWebToken} shared by the CDI-based security tests; only
 * {@link #getRawToken()} carries a value, which is all the token-resolution code reads. Deploying it
 * as a bean is what makes an off-request-thread lookup throw
 * {@link jakarta.enterprise.context.ContextNotActiveException} (the recovery-thread path) rather than
 * simply failing to resolve.
 */
@RequestScoped
public class RequestScopedJsonWebToken implements JsonWebToken {

    /** Raw caller token exposed to tests that assert inbound-token propagation. */
    public static final String RAW_TOKEN = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJjYWxsZXIifQ.caller-signature";

    @Override
    public String getName() {
        return "caller";
    }

    @Override
    public Set<String> getClaimNames() {
        return Set.of();
    }

    @Override
    public <T> T getClaim(String claimName) {
        return null;
    }

    @Override
    public String getRawToken() {
        return RAW_TOKEN;
    }
}
