package org.ikigaidigital.business;

import org.ikigaidigital.domain.model.TimeDeposit;
import org.ikigaidigital.domain.model.TimeDepositDetails;
import org.ikigaidigital.domain.service.DepositAgeing;
import org.ikigaidigital.domain.service.TimeDepositCalculator;
import org.ikigaidigital.repository.api.TimeDepositRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Use cases for time deposits: list them, and apply one month of interest to all of them.
 */
@Service
public class TimeDepositService {


    private final TimeDepositRepository repository;
    private final TimeDepositCalculator calculator;
    private final DepositAgeing depositAgeing;

    public TimeDepositService(TimeDepositRepository repository, TimeDepositCalculator calculator,
                              DepositAgeing depositAgeing) {
        this.repository = repository;
        this.calculator = calculator;
        this.depositAgeing = depositAgeing;
    }

    @Transactional(readOnly = true)
    public List<TimeDepositDetails> getAll() {
        return repository.findAllWithWithdrawals();
    }

    @Transactional
    public void updateAllBalances() {
        List<TimeDeposit> deposits = repository.findAllForUpdate();
        calculator.updateBalance(deposits);
        depositAgeing.advanceOneMonth(deposits);
        repository.saveAll(deposits);
    }
}
