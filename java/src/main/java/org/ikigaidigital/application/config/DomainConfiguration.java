package org.ikigaidigital.application.config;

import org.ikigaidigital.domain.service.DepositAgeing;
import org.ikigaidigital.domain.service.TimeDepositCalculator;
import org.ikigaidigital.domain.service.interest.BasicInterestStrategy;
import org.ikigaidigital.domain.service.interest.InterestStrategy;
import org.ikigaidigital.domain.service.interest.PremiumInterestStrategy;
import org.ikigaidigital.domain.service.interest.StudentInterestStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Wires framework-free domain services as Spring beans, so the domain itself stays annotation-free.
 */
@Configuration(proxyBeanMethods = false)
public class DomainConfiguration {

    // One bean per plan. To add a plan, add an InterestStrategy implementation and register it here.
    @Bean
    BasicInterestStrategy basicInterestStrategy() {
        return new BasicInterestStrategy();
    }

    @Bean
    StudentInterestStrategy studentInterestStrategy() {
        return new StudentInterestStrategy();
    }

    @Bean
    PremiumInterestStrategy premiumInterestStrategy() {
        return new PremiumInterestStrategy();
    }

    @Bean
    TimeDepositCalculator timeDepositCalculator(List<InterestStrategy> strategies) {
        return new TimeDepositCalculator(strategies);
    }

    @Bean
    DepositAgeing depositAgeing() {
        return new DepositAgeing();
    }
}
