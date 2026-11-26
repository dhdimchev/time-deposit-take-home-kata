package org.ikigaidigital.application;

import org.ikigaidigital.domain.service.DepositAgeing;
import org.ikigaidigital.domain.service.TimeDepositCalculator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires framework-free domain services as Spring beans, so the domain itself stays annotation-free.
 */
@Configuration(proxyBeanMethods = false)
public class DomainConfiguration {

    @Bean
    TimeDepositCalculator timeDepositCalculator() {
        return new TimeDepositCalculator();
    }

    @Bean
    DepositAgeing depositAgeing() {
        return new DepositAgeing();
    }
}
