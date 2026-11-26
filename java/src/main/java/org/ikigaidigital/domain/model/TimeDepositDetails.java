package org.ikigaidigital.domain.model;

import java.util.List;

/**
 * A time deposit together with its withdrawals. TimeDeposit itself is a shared class that must not change,
 * so withdrawals are attached here instead.
 */
public record TimeDepositDetails(TimeDeposit deposit, List<Withdrawal> withdrawals) {

    public TimeDepositDetails {
        withdrawals = List.copyOf(withdrawals);
    }
}
