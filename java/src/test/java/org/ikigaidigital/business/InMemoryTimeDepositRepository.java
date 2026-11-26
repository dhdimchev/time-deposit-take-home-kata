package org.ikigaidigital.business;

import org.ikigaidigital.domain.model.TimeDeposit;
import org.ikigaidigital.domain.model.TimeDepositDetails;
import org.ikigaidigital.repository.api.TimeDepositRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-written fake of the persistence port for unit tests.
 */
class InMemoryTimeDepositRepository implements TimeDepositRepository {

    private final List<TimeDepositDetails> details = new ArrayList<>();
    private final List<List<Double>> savedBalances = new ArrayList<>();
    private final List<List<Integer>> savedDays = new ArrayList<>();
    private int findAllForUpdateCalls;

    InMemoryTimeDepositRepository add(TimeDepositDetails deposit) {
        details.add(deposit);
        return this;
    }

    @Override
    public List<TimeDepositDetails> findAllWithWithdrawals() {
        return List.copyOf(details);
    }

    @Override
    public List<TimeDeposit> findAllForUpdate() {
        findAllForUpdateCalls++;
        return details.stream().map(TimeDepositDetails::deposit).toList();
    }

    @Override
    public void saveAll(List<TimeDeposit> deposits) {
        savedBalances.add(deposits.stream().map(TimeDeposit::getBalance).toList());
        savedDays.add(deposits.stream().map(TimeDeposit::getDays).toList());
    }

    List<List<Double>> savedBalances() {
        return savedBalances;
    }

    List<List<Integer>> savedDays() {
        return savedDays;
    }

    int findAllForUpdateCalls() {
        return findAllForUpdateCalls;
    }
}
