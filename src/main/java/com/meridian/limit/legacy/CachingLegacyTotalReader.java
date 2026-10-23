package com.meridian.limit.legacy;

import com.meridian.limit.config.LimitEnforcementProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Reads the legacy daily total, cached briefly in Redis/ElastiCache to keep the
 * call off the hot path. A cache outage degrades to a direct legacy read; it
 * never changes the fail-closed posture of the underlying call.
 */
@Component
public class CachingLegacyTotalReader {

    private static final Logger log = LoggerFactory.getLogger(CachingLegacyTotalReader.class);

    private final LegacyCoreClient legacyCoreClient;
    private final LimitEnforcementProperties props;
    @Nullable
    private final StringRedisTemplate redis;

    public CachingLegacyTotalReader(LegacyCoreClient legacyCoreClient,
                                    LimitEnforcementProperties props,
                                    @Nullable StringRedisTemplate redis) {
        this.legacyCoreClient = legacyCoreClient;
        this.props = props;
        this.redis = redis;
    }

    public BigDecimal read(String customerId, LocalDate txnDate, String currency) {
        String key = cacheKey(customerId, txnDate, currency);

        BigDecimal cached = tryReadCache(key);
        if (cached != null) {
            return cached;
        }

        // Cache miss (or cache down): go to the source. This call is fail-closed.
        BigDecimal total = legacyCoreClient.fetchDailyTotal(customerId, txnDate, currency);
        tryWriteCache(key, total);
        return total;
    }

    private String cacheKey(String customerId, LocalDate txnDate, String currency) {
        return "legacy-total:" + customerId + ":" + txnDate + ":" + currency;
    }

    @Nullable
    private BigDecimal tryReadCache(String key) {
        if (redis == null) {
            return null;
        }
        try {
            String value = redis.opsForValue().get(key);
            return value == null ? null : new BigDecimal(value);
        } catch (DataAccessException | NumberFormatException e) {
            log.debug("Legacy-total cache read skipped ({})", e.toString());
            return null;
        }
    }

    private void tryWriteCache(String key, BigDecimal total) {
        if (redis == null) {
            return;
        }
        try {
            redis.opsForValue().set(key, total.toPlainString(), props.getLegacy().getTotalCacheTtl());
        } catch (DataAccessException e) {
            log.debug("Legacy-total cache write skipped ({})", e.toString());
        }
    }
}
