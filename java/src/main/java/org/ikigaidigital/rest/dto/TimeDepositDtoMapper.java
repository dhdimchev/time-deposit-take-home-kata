package org.ikigaidigital.rest.dto;

import org.ikigaidigital.domain.model.Money;
import org.ikigaidigital.domain.model.TimeDeposit;
import org.ikigaidigital.domain.model.TimeDepositDetails;
import org.ikigaidigital.domain.model.Withdrawal;


/**
 * Maps domain objects to the API models generated from openapi.yaml.
 */
public final class TimeDepositDtoMapper {

    private TimeDepositDtoMapper() {
    }

    public static TimeDepositDto toDto(TimeDepositDetails details) {
        TimeDeposit deposit = details.deposit();
        return new TimeDepositDto(
                deposit.getId(),
                deposit.getPlanType(),
                Money.toCents(deposit.getBalance()),
                deposit.getDays(),
                details.withdrawals().stream().map(TimeDepositDtoMapper::toDto).toList());
    }

    private static WithdrawalDto toDto(Withdrawal withdrawal) {
        return new WithdrawalDto(withdrawal.id(), withdrawal.amount(), withdrawal.date());
    }
}
