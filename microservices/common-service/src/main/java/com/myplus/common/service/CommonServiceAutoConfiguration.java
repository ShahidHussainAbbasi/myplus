package com.myplus.common.service;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Auto-registers shared cross-cutting platform beans for any servlet service that has this module on its
 * classpath. JPA-dependent beans (the demo purge) only register when the service actually has an
 * {@link EntityManagerFactory}, so non-JPA services are unaffected. Runs after JPA auto-config so the
 * {@code @ConditionalOnBean} check sees the EMF.
 */
@AutoConfiguration(after = HibernateJpaAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CommonServiceAutoConfiguration {

    @Bean
    @ConditionalOnBean(EntityManagerFactory.class)
    @ConditionalOnMissingBean
    public DemoPurgeController demoPurgeController() {
        return new DemoPurgeController();
    }
}
