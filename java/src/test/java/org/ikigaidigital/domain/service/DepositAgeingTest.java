package org.ikigaidigital.domain.service;

import org.ikigaidigital.domain.model.TimeDeposit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DepositAgeingTest {

    @Test
    void advancesEveryDepositByThirtyDaysAndLeavesBalancesUntouched() {
        List<TimeDeposit> deposits = List.of(
                new TimeDeposit(1, "basic", 1_200.00, 0),
                new TimeDeposit(2, "premium", 240.00, 45),
                new TimeDeposit(3, "student", 400.00, 365));

        new DepositAgeing().advanceOneMonth(deposits);

        assertThat(deposits).extracting(TimeDeposit::getDays).containsExactly(30, 75, 395);
        assertThat(deposits).extracting(TimeDeposit::getBalance).containsExactly(1_200.00, 240.00, 400.00);
    }
}
