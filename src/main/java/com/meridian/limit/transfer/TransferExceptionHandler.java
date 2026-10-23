package com.meridian.limit.transfer;

import com.meridian.limit.legacy.LegacyTotalUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain failures to HTTP responses.
 *
 * <p>The limit read is fail-closed: when the legacy total is unavailable we
 * reject with 503 rather than risk breaching the limit.
 */
@RestControllerAdvice
public class TransferExceptionHandler {

    @ExceptionHandler(LegacyTotalUnavailableException.class)
    ProblemDetail handleLegacyUnavailable(LegacyTotalUnavailableException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Daily limit could not be verified; transfer rejected (fail-closed).");
        pd.setTitle("Limit verification unavailable");
        return pd;
    }

    /**
     * SD-1: the caller is authenticated but not authorized to transact for the
     * requested customer. 403, not 422.
     */
    @ExceptionHandler(ForbiddenException.class)
    ProblemDetail handleForbidden(ForbiddenException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                "Not authorized to transact for the requested customer.");
        pd.setTitle("Forbidden");
        return pd;
    }
}
