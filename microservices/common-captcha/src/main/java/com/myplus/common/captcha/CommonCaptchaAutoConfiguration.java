package com.myplus.common.captcha;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers the captcha verifier for any service that declares the common-captcha dependency
 * (slice 33, Phase 9). Opt-in — only auth-service (and future consumers) pull it in. Each bean is
 * {@code @ConditionalOnMissingBean} so a service may override.
 */
@AutoConfiguration
@EnableConfigurationProperties(CaptchaProperties.class)
public class CommonCaptchaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CaptchaAttemptService captchaAttemptService() {
        return new CaptchaAttemptService();
    }

    @Bean
    @ConditionalOnMissingBean
    public CaptchaVerifier captchaVerifier(CaptchaProperties properties, CaptchaAttemptService attemptService) {
        return new CaptchaVerifier(properties, attemptService);
    }
}
