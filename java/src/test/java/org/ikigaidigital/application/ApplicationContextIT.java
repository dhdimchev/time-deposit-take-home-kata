package org.ikigaidigital.application;

import org.ikigaidigital.domain.service.TimeDepositCalculator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationContextIT extends PostgresIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoadsWithCalculatorBean() {
        assertThat(context.getBean(TimeDepositCalculator.class)).isNotNull();
    }
}
