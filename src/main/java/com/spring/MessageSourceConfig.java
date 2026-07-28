package com.spring;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.web.util.JsMessageSource;

/**
 * The application's message bundle, deliberately kept OUT of {@link MvcConfig}.
 *
 * It used to be a {@code @Bean} on MvcConfig, which also field-injects {@code LocaleInterceptor}, and that
 * interceptor injects {@code MessageSource} — so building MvcConfig required a bean that MvcConfig itself was
 * still in the middle of creating, and startup failed with "Requested bean is currently in creation". A
 * configuration class must not both define a bean and field-depend on a consumer of it.
 *
 * Messages are not an MVC-config concern anyway: validation messages, the {@code ui.js.*} bundle handed to the
 * browser, and the Thymeleaf views all read from here, so it belongs in its own configuration.
 */
@Configuration
public class MessageSourceConfig {

    @Bean
    public MessageSource messageSource() {
        // JsMessageSource == ReloadableResourceBundleMessageSource plus getMessagesWithPrefix(), which
        // lets the browser be handed the ui.js.* subset of this very bundle (see LocaleInterceptor).
        final JsMessageSource messageSource = new JsMessageSource();
        messageSource.setBasename("classpath:messages");
        messageSource.setUseCodeAsDefaultMessage(true);
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setCacheSeconds(0);
        // Without this, a server whose system locale is (say) de_DE would fall back to messages_de
        // rather than to the base bundle — the same page then renders differently per host.
        // Every language reachable through the whitelisted ?lang= switch has its own bundle, so
        // the base bundle is only ever a safety net.
        messageSource.setFallbackToSystemLocale(false);
        return messageSource;
    }
}
