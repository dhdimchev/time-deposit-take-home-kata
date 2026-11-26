package org.ikigaidigital.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyTest {

    @Test
    void accumulatedDoubleNoiseIsRoundedToCents() {
        assertThat(Money.toCents(0.1 + 0.2)).isEqualByComparingTo("0.30"); // 0.30000000000000004
    }

    @Test
    void wholeAmountsGetTwoDecimals() {
        assertThat(Money.toCents(241.0)).isEqualByComparingTo("241.00");
        assertThat(Money.toCents(241.0).scale()).isEqualTo(2);
    }

    @Test
    void valueOneUlpBelowAWholeAmountRoundsToThatAmount() {
        assertThat(Money.toCents(Math.nextDown(1_201.0))).isEqualByComparingTo("1201.00");
    }
}
