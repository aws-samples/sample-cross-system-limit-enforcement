package com.meridian.limit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Typed configuration for the limit-enforcement domain. See
 * {@code application.yml} under the {@code limit-enforcement} prefix.
 */
@ConfigurationProperties(prefix = "limit-enforcement")
public class LimitEnforcementProperties {

    /** Default per-customer daily limit in currency units. */
    private BigDecimal defaultDailyLimit = new BigDecimal("50000000.00");

    /**
     * Server-side mapping of authenticated principal (Cognito {@code sub}) to
     * the {@code customerId} that principal is allowed to transact for. The
     * mapping is authoritative and is never taken from the client. A real
     * deployment would back this with a datastore; it is config-seeded here.
     */
    private Map<String, String> principalCustomerMap = new HashMap<>();

    /**
     * Additional principal-to-customer bindings as comma-separated
     * {@code sub=customerId} pairs, supplied through the
     * {@code PRINCIPAL_CUSTOMER_MAP} environment variable. A flat string is used
     * because Cognito {@code sub} values do not survive Spring's relaxed binding
     * as map keys in environment variables.
     */
    private String principalCustomerMappings = "";

    /** Currency the limit is expressed in. */
    private String currency = "USD";

    /** How long a RESERVED row holds capacity before the reconciler investigates. */
    private Duration reservationTtl = Duration.ofSeconds(60);

    /** Hard safety valve: force-expire a stuck reservation after this window. */
    private Duration reservationMaxAge = Duration.ofHours(1);

    /** Source of truth for the running total. */
    private ReadSource readSource = ReadSource.COMPOSED;

    @NestedConfigurationProperty
    private Legacy legacy = new Legacy();

    @NestedConfigurationProperty
    private Reconciler reconciler = new Reconciler();

    /** Where the running total's read authority lives during the migration. */
    public enum ReadSource {
        /** legacyTotal + cloudCommitted + cloudInFlight (Phase 1/2). */
        COMPOSED,
        /** cloudCommitted + cloudInFlight only (Phase 3+). */
        CLOUD
    }

    public static class Legacy {
        private String dailyTotalUrl;
        private String executeUrl;
        private String statusUrl;
        private Duration totalCacheTtl = Duration.ofSeconds(5);

        public String getDailyTotalUrl() {
            return dailyTotalUrl;
        }

        public void setDailyTotalUrl(String dailyTotalUrl) {
            this.dailyTotalUrl = dailyTotalUrl;
        }

        public String getExecuteUrl() {
            return executeUrl;
        }

        public void setExecuteUrl(String executeUrl) {
            this.executeUrl = executeUrl;
        }

        public String getStatusUrl() {
            return statusUrl;
        }

        public void setStatusUrl(String statusUrl) {
            this.statusUrl = statusUrl;
        }

        public Duration getTotalCacheTtl() {
            return totalCacheTtl;
        }

        public void setTotalCacheTtl(Duration totalCacheTtl) {
            this.totalCacheTtl = totalCacheTtl;
        }
    }

    public static class Reconciler {
        private boolean enabled = true;
        private String cron = "0 * * * * *";
        private int batchSize = 200;
        /** Use FOR UPDATE SKIP LOCKED (Aurora/MySQL). Disable for H2 (local/dev). */
        private boolean skipLocked = true;

        public boolean isEnabled() {
            return enabled;
        }

        public boolean isSkipLocked() {
            return skipLocked;
        }

        public void setSkipLocked(boolean skipLocked) {
            this.skipLocked = skipLocked;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getCron() {
            return cron;
        }

        public void setCron(String cron) {
            this.cron = cron;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }
    }

    public BigDecimal getDefaultDailyLimit() {
        return defaultDailyLimit;
    }

    public void setDefaultDailyLimit(BigDecimal defaultDailyLimit) {
        this.defaultDailyLimit = defaultDailyLimit;
    }

    public Map<String, String> getPrincipalCustomerMap() {
        return principalCustomerMap;
    }

    public void setPrincipalCustomerMap(Map<String, String> principalCustomerMap) {
        this.principalCustomerMap = principalCustomerMap;
    }

    public String getPrincipalCustomerMappings() {
        return principalCustomerMappings;
    }

    public void setPrincipalCustomerMappings(String principalCustomerMappings) {
        this.principalCustomerMappings = principalCustomerMappings;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Duration getReservationTtl() {
        return reservationTtl;
    }

    public void setReservationTtl(Duration reservationTtl) {
        this.reservationTtl = reservationTtl;
    }

    public Duration getReservationMaxAge() {
        return reservationMaxAge;
    }

    public void setReservationMaxAge(Duration reservationMaxAge) {
        this.reservationMaxAge = reservationMaxAge;
    }

    public ReadSource getReadSource() {
        return readSource;
    }

    public void setReadSource(ReadSource readSource) {
        this.readSource = readSource;
    }

    public Legacy getLegacy() {
        return legacy;
    }

    public void setLegacy(Legacy legacy) {
        this.legacy = legacy;
    }

    public Reconciler getReconciler() {
        return reconciler;
    }

    public void setReconciler(Reconciler reconciler) {
        this.reconciler = reconciler;
    }
}
