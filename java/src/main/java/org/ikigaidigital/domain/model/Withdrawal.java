package org.ikigaidigital.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A withdrawal made from a time deposit. Read-only history: it does not affect interest.
 */
public record Withdrawal(int id, BigDecimal amount, LocalDate date) {
}
