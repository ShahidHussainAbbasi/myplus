package com.myplus.common.service;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Auto-registers shared cross-cutting platform beans for any servlet service that has this module on its
 * classpath. JPA-dependent beans (the demo purge) only register when the service actually has an
 * {@link EntityManagerFactory}, so non-JPA services are unaffected. Runs after JPA auto-config so the
 * {@code @ConditionalOnBean} check sees the EMF.
 */
@AutoConfiguration(after = HibernateJpaAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CommonServiceAutoConfiguration {

    /**
     * The purge is refused under the {@code prod} profile unless {@code app.demo.purge-enabled=true}. Both are
     * passed in here rather than read inside the controller, so the guard can be unit-tested without a context
     * — see {@code DemoPurgeControllerProdGuardTest}.
     */
    @Bean
    @ConditionalOnBean(EntityManagerFactory.class)
    @ConditionalOnMissingBean
    public DemoPurgeController demoPurgeController(
            Environment environment,
            @Value("${app.demo.purge-enabled:false}") boolean purgeEnabled) {
        return new DemoPurgeController(environment, purgeEnabled);
    }

    /** PROD-DEL: every JPA service answers "is this product still used here?" for catalog's permanent delete. */
    @Bean
    @ConditionalOnBean(EntityManagerFactory.class)
    @ConditionalOnMissingBean
    public ProductUsageController productUsageController() {
        return new ProductUsageController();
    }
}
