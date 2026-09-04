package com.myplus.auth;

import org.modelmapper.ModelMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * C3c — {@code CommonWebAutoConfiguration} is excluded, exactly as business-service excludes it.
 *
 * <p>auth-service gained {@code common-web} because common-settings needs {@code ApiResponse} (and declares it
 * {@code provided}). That auto-configuration also registers a bean named {@code globalExceptionHandler}, and
 * auth-service has owned a {@code com.myplus.auth.exception.GlobalExceptionHandler} — same bean name — since
 * long before. With bean-definition overriding disabled, the context refused to start at all.
 *
 * <p>Excluding the shared one rather than renaming the local one keeps auth-service's own error contract
 * intact: its handler shapes the responses the login and registration flows return, and the monolith's client
 * parses them. Swapping in the generic handler would change those payloads for a purely mechanical reason.
 *
 * <p>The classes themselves stay on the classpath — only the auto-configuration is skipped — so the
 * common-settings controllers still answer in the shared {@code ApiResponse} envelope.
 */
/*
 * E4 — @EnableScheduling, and it is load-bearing rather than decorative.
 *
 * The control-plane audit outbox delivers AFTER_COMMIT, and the @Scheduled relay in AuditEmitter is what
 * re-drives anything that did not land — a restart mid-delivery, audit-service briefly down, a network blip.
 * auth-service had no scheduling enabled at all, so without this the annotation would be present, reviewed,
 * and inert: rows would sit PENDING forever and the only symptom would be a trail with gaps nobody could
 * explain. Same class of silent failure as common-settings' @Import-wired beans.
 */
@EnableScheduling
@SpringBootApplication(exclude = com.myplus.common.web.CommonWebAutoConfiguration.class)
public class AuthServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }

    @Bean
    public ModelMapper modelMapper() {
        return new ModelMapper();
    }
}
