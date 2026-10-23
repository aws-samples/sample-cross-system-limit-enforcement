package com.meridian.limit.transfer;

import com.meridian.limit.config.LimitEnforcementProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Supplies the daily limit for a customer (Step 1 of the online flow).
 *
 * <p>Default implementation returns the configured regulator limit for every
 * customer. Replace with a lookup (e.g. risk tier, account type) as needed.
 */
@Component
public class CustomerLimitProvider {

    private final LimitEnforcementProperties props;

    public CustomerLimitProvider(LimitEnforcementProperties props) {
        this.props = props;
    }

    public BigDecimal dailyLimitFor(String customerId) {
        return props.getDefaultDailyLimit();
    }
}
