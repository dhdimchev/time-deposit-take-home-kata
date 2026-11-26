package org.ikigaidigital.rest.dto;

import org.ikigaidigital.domain.model.TimeDeposit;
import org.ikigaidigital.domain.model.TimeDepositDetails;
import org.ikigaidigital.domain.model.Withdrawal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TimeDepositDtoMapperTest {

    @Test
    void mapsAllFiveFieldsAndWithdrawals() {
        Withdrawal withdrawal = new Withdrawal(2, new BigDecimal("100.00"), LocalDate.of(2026, 2, 1));

        TimeDepositDto dto = TimeDepositDtoMapper.toDto(
                new TimeDepositDetails(new TimeDeposit(7, "premium", 241.0, 60), List.of(withdrawal)));

        assertThat(dto.getId()).isEqualTo(7);
        assertThat(dto.getPlanType()).isEqualTo("premium");
        assertThat(dto.getBalance()).isEqualByComparingTo("241.00");
        assertThat(dto.getBalance().scale()).isEqualTo(2);
        assertThat(dto.getDays()).isEqualTo(60);
        assertThat(dto.getWithdrawals()).singleElement().satisfies(withdrawalDto -> {
            assertThat(withdrawalDto.getId()).isEqualTo(2);
            assertThat(withdrawalDto.getAmount()).isEqualByComparingTo("100.00");
            assertThat(withdrawalDto.getDate()).isEqualTo(LocalDate.of(2026, 2, 1));
        });
    }

    @Test
    void balanceDoubleNoiseIsRoundedToCents() {
        TimeDepositDto dto = TimeDepositDtoMapper.toDto(
                new TimeDepositDetails(new TimeDeposit(1, "basic", 0.1 + 0.2, 31), List.of()));

        assertThat(dto.getBalance()).isEqualByComparingTo("0.30");
        assertThat(dto.getWithdrawals()).isEmpty();
    }
}
