package org.ikigaidigital.application.config;

import org.ikigaidigital.business.BalanceUpdateSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Binds {@code time-deposits.balance-update.*} and exposes it to the business layer as plain settings.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BalanceUpdateConfiguration.BalanceUpdateProperties.class)
public class BalanceUpdateConfiguration {

    @ConfigurationProperties("time-deposits.balance-update")
    record BalanceUpdateProperties(@DefaultValue("1000") int batchSize,
                                   @DefaultValue("100ms") Duration rescanBackoff) {
    }

    @Bean
    BalanceUpdateSettings balanceUpdateSettings(BalanceUpdateProperties properties) {
        return new BalanceUpdateSettings(properties.batchSize(), properties.rescanBackoff());
    }
}
