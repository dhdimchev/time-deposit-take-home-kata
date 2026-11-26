package org.ikigaidigital.domain.model;

import java.util.Optional;

/**
 * Known time deposit plans and the plan-type value stored on {@link TimeDeposit#getPlanType()}.
 * Values are matched case-sensitively.
 */
public enum PlanType {
    BASIC("basic", 0.01),
    STUDENT("student", 0.03),
    PREMIUM("premium", 0.05);

    private final String value;
    private final double annualInterestRate;

    PlanType(String value, double annualInterestRate) {
        this.value = value;
        this.annualInterestRate = annualInterestRate;
    }

    public String getValue() {
        return value;
    }

    public double getAnnualInterestRate() {
        return annualInterestRate;
    }

    /**
     * @return the plan whose value matches exactly, or empty for an unknown value
     * @throws NullPointerException if {@code value} is null (preserves the legacy calculator behaviour)
     */
    public static Optional<PlanType> fromValue(String value) {
        for (PlanType planType : values()) {
            if (value.equals(planType.getValue())) {
                return Optional.of(planType);
            }
        }
        return Optional.empty();
    }

}
