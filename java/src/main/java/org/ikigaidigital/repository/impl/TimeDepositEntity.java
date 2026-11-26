package org.ikigaidigital.repository.impl;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "time_deposits")
class TimeDepositEntity {

    @Id
    private Integer id;

    @Column(name = "plan_type", nullable = false)
    private String planType;

    @Column(nullable = false)
    private Integer days;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Column(name = "last_interest_run_id")
    private Integer lastInterestRunId;

    protected TimeDepositEntity() {
    }

    Integer getId() {
        return id;
    }

    String getPlanType() {
        return planType;
    }

    Integer getDays() {
        return days;
    }

    BigDecimal getBalance() {
        return balance;
    }

    void setDays(Integer days) {
        this.days = days;
    }

    void setLastInterestRunId(Integer lastInterestRunId) {
        this.lastInterestRunId = lastInterestRunId;
    }

    void setBalance(BigDecimal balance) {
        this.balance = balance;
    }
}
