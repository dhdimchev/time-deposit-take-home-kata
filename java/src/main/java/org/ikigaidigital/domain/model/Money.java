package org.ikigaidigital.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money policy shared by every adapter: amounts are exposed and stored with two decimals.
 * The domain keeps balances as {@code double}; this converts them at the boundary.
 */
public final class Money {

    private Money() {
    }

    /** Converts a domain amount to cents, normalising accumulated double noise (e.g. 0.30000000000000004 -> 0.30). */
    public static BigDecimal toCents(double amount) {
        return BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP);
    }
}
