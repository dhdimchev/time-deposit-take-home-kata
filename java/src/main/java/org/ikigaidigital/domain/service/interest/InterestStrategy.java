package org.ikigaidigital.domain.service.interest;

import org.ikigaidigital.domain.model.TimeDeposit;

/**
 * The interest rule of one plan type. Adding a plan means adding an implementation and registering it with
 * {@link org.ikigaidigital.domain.service.TimeDepositCalculator}; the calculator itself is not edited.
 */
public interface InterestStrategy {

    String planType();

    double monthlyInterest(TimeDeposit deposit);
}
