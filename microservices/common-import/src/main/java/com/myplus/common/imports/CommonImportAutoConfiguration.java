package com.myplus.common.imports;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Wires the import engine and the spec registry for any service on the classpath.
 *
 * <p>Unconditional, unlike {@code CommonCreditAutoConfiguration}: an engine with no specs is harmless — the
 * registry is simply empty and {@code /import/entities} answers with an empty list, so the grid draws no
 * buttons. There is nothing to fail on first use, so there is nothing to gate on.
 *
 * <p>Registered through META-INF/spring/…AutoConfiguration.imports because this package sits outside every
 * consumer's {@code @ComponentScan} root.
 */
@AutoConfiguration
public class CommonImportAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ImportEngine importEngine() {
        return new ImportEngine();
    }

    /**
     * Collects every {@link ImportSpec} bean the consuming service declares.
     *
     * <p>{@link ObjectProvider} rather than a constructor {@code List}, so a service with no specs at all
     * still starts — which is every service except business-service today.
     */
    @Bean
    @ConditionalOnMissingBean
    public ImportSpecRegistry importSpecRegistry(ObjectProvider<ImportSpec<?>> specs) {
        List<ImportSpec<?>> found = specs.orderedStream().toList();
        return new ImportSpecRegistry(found);
    }
}
