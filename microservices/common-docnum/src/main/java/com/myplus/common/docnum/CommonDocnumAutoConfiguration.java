package com.myplus.common.docnum;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * EX-0c — gives every service that depends on {@code common-docnum} a {@link DocumentNumberService}.
 *
 * <p>Unconditional, unlike {@code CommonCreditAutoConfiguration}'s {@code @ConditionalOnBean(CreditStore)}: the
 * store here is a Spring Data repository, registered by another auto-configuration, and an
 * {@code @ConditionalOnBean} evaluated before it would silently leave this bean out. The service instead resolves
 * its store on first use and fails with a clear message if there is none — loud, never absent.
 *
 * <p>Registered through META-INF/spring/…AutoConfiguration.imports because this package sits outside every
 * consumer's {@code @ComponentScan} root.
 */
@AutoConfiguration
public class CommonDocnumAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DocumentNumberService documentNumberService(ObjectProvider<DocumentCounterStore> store,
                                                       ObjectProvider<DocumentNumberService> self) {
        return new DocumentNumberService(store, self);
    }
}
