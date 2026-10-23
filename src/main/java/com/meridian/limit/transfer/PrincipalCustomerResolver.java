package com.meridian.limit.transfer;

import com.meridian.limit.config.LimitEnforcementProperties;
import org.springframework.stereotype.Component;

/**
 * Resolves an authenticated principal (Cognito {@code sub}) to the
 * {@code customerId} that principal is authorized to transact for.
 *
 * <p>The mapping is held server-side (see
 * {@link LimitEnforcementProperties#getPrincipalCustomerMap()}) and is never
 * taken from the client. This is the enforcement point for SD-1: the API
 * Gateway authorizer proves <em>authentication</em>; this resolver supplies the
 * <em>authorization</em> binding the request's {@code customerId} is checked
 * against.
 */
@Component
public class PrincipalCustomerResolver {

    private final LimitEnforcementProperties properties;

    public PrincipalCustomerResolver(LimitEnforcementProperties properties) {
        this.properties = properties;
    }

    /**
     * Returns the {@code customerId} the given principal is authorized to act
     * for, or {@code null} if the principal is unknown/unmapped.
     *
     * @param principalSub the authenticated Cognito {@code sub} claim
     */
    public String resolveCustomerId(String principalSub) {
        if (principalSub == null || principalSub.isBlank()) {
            return null;
        }
        String mapped = properties.getPrincipalCustomerMap().get(principalSub);
        if (mapped != null) {
            return mapped;
        }
        return lookupInMappings(principalSub, properties.getPrincipalCustomerMappings());
    }

    private static String lookupInMappings(String principalSub, String mappings) {
        if (mappings == null || mappings.isBlank()) {
            return null;
        }
        for (String pair : mappings.split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0 || eq == pair.length() - 1) {
                continue; // skip malformed entries rather than guess
            }
            if (pair.substring(0, eq).trim().equals(principalSub)) {
                return pair.substring(eq + 1).trim();
            }
        }
        return null;
    }
}
