package org.ikigaidigital.repository.impl;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

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

    @OneToMany(mappedBy = "timeDeposit")
    @OrderBy("id")
    private List<WithdrawalEntity> withdrawals = new ArrayList<>();

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

    void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    List<WithdrawalEntity> getWithdrawals() {
        return withdrawals;
    }
}
