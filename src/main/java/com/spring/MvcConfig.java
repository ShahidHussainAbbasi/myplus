package com.spring;

import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
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
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

import com.validation.EmailValidator;
import com.validation.PasswordMatchesValidator;
import com.web.util.SupportedLocaleChangeInterceptor;

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
/*
    @Autowired
    private MessageSource messageSource;
*/
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

    @Bean
    public LocaleResolver localeResolver() {
        final CookieLocaleResolver cookieLocaleResolver = new CookieLocaleResolver();
        cookieLocaleResolver.setDefaultLocale(Locale.ENGLISH);
        return cookieLocaleResolver;
    }

    @Bean
    public MessageSource messageSource() {
    final ReloadableResourceBundleMessageSource messageSource = new ReloadableResourceBundleMessageSource();
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
        validator.setValidationMessageSource(messageSource());
        return validator;
    }

}