package com.meridian.limit.transfer;

/**
 * Thrown when the authenticated principal is not authorized to transact for the
 * {@code customerId} in the request. Mapped to {@code 403 Forbidden} — the
 * caller is authenticated but lacks authorization for this customer (not a
 * {@code 422}, which would imply the request itself was well-formed but
 * semantically rejected by the limit domain).
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
