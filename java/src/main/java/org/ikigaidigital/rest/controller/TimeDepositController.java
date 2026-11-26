package org.ikigaidigital.rest.controller;

import org.ikigaidigital.business.TimeDepositService;
import org.ikigaidigital.rest.dto.TimeDepositDto;
import org.ikigaidigital.rest.dto.TimeDepositDtoMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST adapter implementing the interface generated from openapi.yaml.
 */
@RestController
public class TimeDepositController implements TimeDepositsApi {

    private final TimeDepositService timeDepositService;

    public TimeDepositController(TimeDepositService timeDepositService) {
        this.timeDepositService = timeDepositService;
    }

    @Override
    public ResponseEntity<List<TimeDepositDto>> getAllTimeDeposits() {
        return ResponseEntity.ok(timeDepositService.getAll().stream().map(TimeDepositDtoMapper::toDto).toList());
    }

    @Override
    public ResponseEntity<Void> updateAllBalances() {
        timeDepositService.updateAllBalances();
        return ResponseEntity.noContent().build();
    }
}
