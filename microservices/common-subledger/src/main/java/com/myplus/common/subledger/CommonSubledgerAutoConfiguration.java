package com.myplus.common.subledger;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Registers {@link SubledgerService} for any service with this module on its classpath.
 *
 * Same reason as {@code CommonOutboxAutoConfiguration}: the bean lives in {@code com.myplus.common.subledger},
 * outside every consumer's {@code @ComponentScan} root, so scanning alone would never find it. Registered via
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports instead, so a consumer needs
 * no scan-path change.
 *
 * Unconditional: {@code SubledgerService} injects {@code FinanceClient} as {@code required = false} and degrades
 * to "allocate locally, skip the ledger record", so a service without finance wired still works.
 *
 * {@link AgingCalculator} needs no registration — it is a static utility.
 */
@AutoConfiguration
@Import(SubledgerService.class)
public class CommonSubledgerAutoConfiguration {
}
