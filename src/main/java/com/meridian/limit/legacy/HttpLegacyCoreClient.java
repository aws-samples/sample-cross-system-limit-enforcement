package com.meridian.limit.legacy;

import com.meridian.limit.config.LimitEnforcementProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * HTTP-backed {@link LegacyCoreClient}. The daily-total read is guarded by a
 * Resilience4j circuit breaker whose fallback throws
 * {@link LegacyTotalUnavailableException} — i.e. fail-closed.
 *
 * <p>The short-TTL cache of the legacy total (ElastiCache in production) lives
 * in {@link CachingLegacyTotalReader}, which wraps this client.
 *
 * <p>Disabled under the {@code stub-legacy} profile, where an in-process stub
 * replaces it for end-to-end testing.
 */
@Component
@Profile("!stub-legacy")
public class HttpLegacyCoreClient implements LegacyCoreClient {

    private static final Logger log = LoggerFactory.getLogger(HttpLegacyCoreClient.class);

    private final RestClient restClient;
    private final LimitEnforcementProperties props;

    public HttpLegacyCoreClient(RestClient restClient, LimitEnforcementProperties props) {
        this.restClient = restClient;
        this.props = props;
    }

    @Override
    @CircuitBreaker(name = "legacyDailyTotal", fallbackMethod = "dailyTotalFallback")
    public BigDecimal fetchDailyTotal(String customerId, LocalDate txnDate, String currency) {
        DailyTotalResponse body = restClient.get()
                .uri(props.getLegacy().getDailyTotalUrl() + "?customerId={c}&date={d}&currency={cur}",
                        customerId, txnDate.toString(), currency)
                .retrieve()
                .body(DailyTotalResponse.class);
        if (body == null || body.total() == null) {
            throw new LegacyTotalUnavailableException("Legacy daily-total response was empty", null);
        }
        return body.total();
    }

    @SuppressWarnings("unused") // referenced by @CircuitBreaker fallbackMethod
    private BigDecimal dailyTotalFallback(String customerId, LocalDate txnDate, String currency, Throwable t) {
        log.warn("Legacy daily-total unavailable for customer={} date={}: {}", customerId, txnDate, t.toString());
        throw new LegacyTotalUnavailableException("Legacy daily-total unavailable; failing closed", t);
    }

    @Override
    public CoreExecutionResult execute(String transferId, String customerId, BigDecimal amount, String currency) {
        try {
            ExecuteResponse body = restClient.post()
                    .uri(props.getLegacy().getExecuteUrl())
                    .body(Map.of(
                            "transferId", transferId,
                            "customerId", customerId,
                            "amount", amount,
                            "currency", currency))
                    .retrieve()
                    .body(ExecuteResponse.class);
            if (body != null && body.settled()) {
                return CoreExecutionResult.SUCCESS;
            }
            return CoreExecutionResult.FAILURE;
        } catch (org.springframework.web.client.ResourceAccessException timeout) {
            // I/O timeout / connection issue: money may or may not have moved.
            log.warn("Legacy execute timed out for transfer={}: {}", transferId, timeout.toString());
            return CoreExecutionResult.TIMEOUT;
        } catch (RestClientResponseException e) {
            // A definitive HTTP error response from the core: treat as failure.
            log.warn("Legacy execute failed for transfer={}: {}", transferId, e.getStatusCode());
            return CoreExecutionResult.FAILURE;
        }
    }

    @Override
    public CoreSettlementStatus fetchStatus(String transferId) {
        try {
            StatusResponse body = restClient.get()
                    .uri(props.getLegacy().getStatusUrl() + "?transferId={t}", transferId)
                    .retrieve()
                    .body(StatusResponse.class);
            if (body == null || body.status() == null) {
                return CoreSettlementStatus.UNKNOWN;
            }
            return switch (body.status().toUpperCase()) {
                case "SETTLED" -> CoreSettlementStatus.SETTLED;
                case "NOT_FOUND", "FAILED", "REJECTED" -> CoreSettlementStatus.NOT_SETTLED;
                default -> CoreSettlementStatus.UNKNOWN;
            };
        } catch (RestClientResponseException | org.springframework.web.client.ResourceAccessException e) {
            log.warn("Legacy status query indeterminate for transfer={}: {}", transferId, e.toString());
            return CoreSettlementStatus.UNKNOWN;
        }
    }

    // --- wire DTOs -----------------------------------------------------------

    record DailyTotalResponse(BigDecimal total) {
    }

    record ExecuteResponse(boolean settled) {
    }

    record StatusResponse(String status) {
    }
}
