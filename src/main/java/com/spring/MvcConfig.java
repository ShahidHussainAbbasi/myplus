package com.spring;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.Validator;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.context.request.RequestContextListener;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.DefaultServletHandlerConfigurer;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

import com.validation.EmailValidator;
import com.validation.PasswordMatchesValidator;
import com.web.util.SupportedLocaleChangeInterceptor;
import com.web.util.SupportedLocaleResolver;

@Configuration
@ComponentScan(basePackages = { "com.web" })
@EnableWebMvc
public class MvcConfig implements WebMvcConfigurer {

 /*   public MvcConfig() {
        super();
    }*/
    

/*    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        super.addInterceptors(registry);
        registry.addInterceptor(activityInterceptor);
    }*/    
    /** From {@link MessageSourceConfig} — safe to inject now that this class no longer defines the bean. */
    @Autowired
    private MessageSource messageSource;

    @Override
    public void addViewControllers(final ViewControllerRegistry registry) {
//        registry.addViewController("/").setViewName("forward:/login");
//        registry.addViewController("/").setViewName("forward:/home");
//        registry.addViewController("").setViewName("forward:home.html");

        registry.addViewController("/home").setViewName("maxtheservice_dashboard");
        registry.addViewController("/login").setViewName("login");        
//        registry.addViewController("/login");
    //    registry.addViewController("/login");
        registry.addViewController("/loginRememberMe");
        registry.addViewController("/customLogin");
        registry.addViewController("/registration.html");
        registry.addViewController("/registrationCaptcha.html");
        registry.addViewController("/logout").setViewName("logout");;
        registry.addViewController("/homepage.html");
        registry.addViewController("/expiredAccount.html");
        registry.addViewController("/badUser.html");
        registry.addViewController("/emailError.html");
//        registry.addViewController("/home.html");
        registry.addViewController("/invalidSession.html");
        registry.addViewController("/console.html");
        registry.addViewController("/admin.html");
        registry.addViewController("/successRegister.html");
        registry.addViewController("/forgetPassword.html");
        registry.addViewController("/updatePassword.html");
        registry.addViewController("/changePassword.html");
        registry.addViewController("/users.html");
        registry.addViewController("/qrcode.html");
        registry.addViewController("/hospital.html");
        registry.addViewController("/donator").setViewName("donator");
        registry.addViewController("/services").setViewName("maxtheservice_dashboard");
        // /businessDashboard is served by BusinessDashboardController — ONE commerce dashboard for all commerce
        // verticals (POS/BUSINESS, Pharmacy/PHARMA, Store/ECOMMERCE); it sets `module` from the logged-in user's
        // type so module-theme.js white-labels the single template (slice 36). No separate per-vertical routes.
        registry.addViewController("/welfareDashboard").setViewName("welfareDashboard");
        registry.addViewController("/educationDashboard").setViewName("educationDashboard");
        registry.addViewController("/agricultureDashboard").setViewName("agricultureDashboard");
        registry.addViewController("/islamicChannels").setViewName("/islamicChannels/islamicChannels");
    }

    @Override
    public void configureDefaultServletHandling(final DefaultServletHandlerConfigurer configurer) {
        // configurer.enable();
        configurer.enable("default"); 
    }

    // @Override
    // public void addResourceHandlers(final ResourceHandlerRegistry registry) {
    //     registry.addResourceHandler("/resources/**").addResourceLocations("/", "/resources/");
    // }

    private static final String[] CLASSPATH_RESOURCE_LOCATIONS = {
        "classpath:/META-INF/resources/",
        "classpath:/resources/",
        "classpath:/static/",
        "classpath:/public/"
    };
    
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (!registry.hasMappingForPattern("/**")) {
            registry.addResourceHandler("/**")
                    .addResourceLocations(CLASSPATH_RESOURCE_LOCATIONS);
        }
    }

    // @Override
    // public void addResourceHandlers(ResourceHandlerRegistry registry) {
    //     registry.addResourceHandler("/**").addResourceLocations("classpath:/static/", "classpath:/public/");
    // }    

    @org.springframework.beans.factory.annotation.Autowired
    private com.web.util.FeatureFlagsInterceptor featureFlagsInterceptor;

    @Autowired
    private com.web.util.LocaleInterceptor localeInterceptor;

    @Override
    public void addInterceptors(final InterceptorRegistry registry) {
        // ?lang= is user-supplied, so it is whitelisted to the shipped languages — an unsupported
        // tag would otherwise be stored in the locale cookie and every page would render raw
        // message keys until the user cleared it. See SupportedLocaleChangeInterceptor.
        final LocaleChangeInterceptor localeChangeInterceptor = new SupportedLocaleChangeInterceptor();
        localeChangeInterceptor.setParamName("lang");
        localeChangeInterceptor.setIgnoreInvalidLocale(true);
        registry.addInterceptor(localeChangeInterceptor);
        // Expose captcha/2FA feature flags to every view (incl. static view-controllers).
        registry.addInterceptor(featureFlagsInterceptor);
        // Expose the active language + text direction to every view — same reason as above.
        registry.addInterceptor(localeInterceptor);
    }

    // beans

    /** Organization default language — the fallback when a visitor's browser asks for nothing we ship. */
    @Value("${app.locale.default:en}")
    private String defaultLanguageTag;

    @Bean
    public LocaleResolver localeResolver() {
        // Was CookieLocaleResolver with a hardcoded ENGLISH default, so a first-time visitor whose
        // browser asked for Urdu or Arabic still got English. SupportedLocaleResolver checks the
        // visitor's own choice, then Accept-Language (region), then this org default.
        return new SupportedLocaleResolver("MYPLUS_LOCALE", defaultLanguageTag);
    }

    // The messageSource bean lives in MessageSourceConfig, NOT here. Defining it here while this class also
    // field-injects LocaleInterceptor (which needs a MessageSource) made MvcConfig depend on a bean it was
    // itself still creating — an unresolvable cycle at startup. Don't move it back.

    @Bean
    public EmailValidator usernameValidator() {
        return new EmailValidator();
    }

    @Bean
    public PasswordMatchesValidator passwordMatchesValidator() {
        return new PasswordMatchesValidator();
    }

    @Bean
    @ConditionalOnMissingBean(RequestContextListener.class)
    public RequestContextListener requestContextListener() {
        return new RequestContextListener();
    }

    @Override
    public Validator getValidator() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        // The injected bean, not a self-call — messageSource() no longer exists on this class.
        validator.setValidationMessageSource(messageSource);
        return validator;
    }

}