package com.myplus.common.credit;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Builds a {@link CreditService} for any service that supplies a {@link CreditStore} bean.
 *
 * Conditional on the store (unlike the outbox/subledger auto-configurations, which are unconditional): without
 * somewhere to keep the ledger a credit service has nothing to operate on, so the module stays inert rather than
 * exposing a bean that would fail on first use. Same shape as {@code CommonSettingsAutoConfiguration}.
 *
 * Registered through META-INF/spring/…AutoConfiguration.imports because this package sits outside every
 * consumer's {@code @ComponentScan} root — the footgun already hit twice in this codebase.
 */
@AutoConfiguration
@ConditionalOnBean(CreditStore.class)
public class CommonCreditAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CreditService creditService(CreditStore store) {
        return new CreditService(store);
    }
}
