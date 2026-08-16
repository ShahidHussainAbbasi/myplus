package com.spring;

import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
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
import org.springframework.web.servlet.resource.ResourceUrlEncodingFilter;
import org.springframework.web.servlet.resource.VersionResourceResolver;

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
        // Slice 3.1 — the guardian portal is a SEPARATE page, not the education dashboard with staff
        // sections hidden. Hiding them in CSS would leave them one inspector-click away; a distinct
        // template renders none of that chrome at all. Its data comes from /portal/* only.
        // Named for the platform's <audience>Dashboard convention and the GUARDIAN domain entity —
        // "guardian" is the term used everywhere, code and UI alike.
        registry.addViewController("/guardianDashboard").setViewName("guardianDashboard");
        // Slice 3.3 — the student portal, on the same terms and for the same reason: a separate template
        // that renders no staff chrome at all, rather than the education dashboard with sections hidden.
        // Its data comes from /portal/my/* only.
        registry.addViewController("/studentDashboard").setViewName("studentDashboard");
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

    /**
     * PERF-2 — the asset directories served with content-hash URLs and a one-year immutable cache.
     *
     * These are every prefix the templates actually reference through {@code @{...}} (/js ×99, /css ×36,
     * /bootstrap ×12, /img ×8, /jQExp ×4) plus /images (reached from CSS {@code url()}) and /webjars.
     *
     * ⚠️ All seven are DIRECTORY prefixes on purpose. SecSecurityConfig permits "/css/**", "/js/**",
     * "/images/**", "/webjars/**", "/bootstrap/**", "/jQExp/**" as prefixes, so a hashed URL keeps exactly
     * the same shape and stays permitted. Root-level assets are deliberately NOT versioned: "/main.css",
     * "/*.png", "/*.ico", "/*.jpeg" are permitted by exact single-segment patterns, and a hashed
     * "/main-<md5>.css" would match none of them, fall through to anyRequest().hasAuthority(...) and 302
     * anonymous visitors to /login. That is precisely the pwstrength.js defect (audit §4d-i) and it is not
     * being re-invented here.
     */
    private static final String[] VERSIONED_ASSET_DIRS = {
        "js", "css", "images", "img", "bootstrap", "jQExp", "webjars"
    };

    /** max-age for the hashed tier. Safe at a year precisely BECAUSE the URL changes when the bytes do. */
    @Value("${app.static.versioned-cache-seconds:31536000}")
    private long versionedCacheSeconds;

    /** max-age for everything else (root-level images, main.css, favicons) — these are NOT hashed. */
    @Value("${app.static.plain-cache-seconds:3600}")
    private long plainCacheSeconds;

    /** Cache resolved resources and their hashes in memory. See the note in addResourceHandlers(). */
    @Value("${app.static.chain-cache:true}")
    private boolean staticChainCache;

    /**
     * PERF-2 (audit finding F1). Static assets used to go out with NO Cache-Control at all, so every one of
     * the ~80 requests on a page was re-issued as a conditional GET on every navigation — and this app
     * navigates by full page load. On a 300 ms link that is seconds of dead time per screen.
     *
     * ⚠️ WHY THIS IS SET IN CODE AND NOT IN application-prod.properties
     * spring.web.resources.cache.period is read by WebMvcAutoConfiguration, which the @EnableWebMvc on this
     * class disables wholesale. Prod-hardening P3 shipped that property and it has never done anything. The
     * period has to be set imperatively, here, on the handler this class registers by hand.
     *
     * ⚠️ THE CACHE PERIOD AND THE VERSIONING MUST STAY TOGETHER
     * A one-year cache without content-hash URLs means a deploy can never reach a browser that already
     * cached the file. ContentVersionStrategy makes the md5 of the bytes part of the URL, so a changed file
     * is a different URL and is always fetched. Never raise the period without the resolver, and never drop
     * the resolver while the period is high.
     *
     * ⚠️ THE TRAP IN THE PER-DIRECTORY HANDLERS
     * ResourceHttpRequestHandler resolves the path WITHIN THE MAPPING against the locations. Mapped at
     * "/**" with location classpath:/static/, "/js/a.js" -> "js/a.js" -> classpath:/static/js/a.js. Mapped
     * at "/js/**" with the SAME location, it becomes "a.js" -> classpath:/static/a.js -> 404 for every
     * script on the site. Hence locationsUnder(dir), which suffixes each location with the directory.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        final CacheControl versioned = CacheControl.maxAge(versionedCacheSeconds, TimeUnit.SECONDS)
                .cachePublic()
                .immutable();

        for (String dir : VERSIONED_ASSET_DIRS) {
            if (registry.hasMappingForPattern("/" + dir + "/**")) {
                continue;
            }
            registry.addResourceHandler("/" + dir + "/**")
                    .addResourceLocations(locationsUnder(dir))
                    .setCacheControl(versioned)
                    // Chain of Responsibility: CachingResourceResolver -> VersionResourceResolver ->
                    // PathResourceResolver. resourceChain(true) also inserts a CssLinkResourceTransformer
                    // automatically once a version resolver is present, which rewrites url(...) inside CSS
                    // so the images a stylesheet references are hashed too.
                    //
                    // chain cache ON in dev as well as prod: this app serves from target/classes and every
                    // static change already needs a rebuild + restart, which drops the cache. With it off,
                    // rendering a page would md5 every referenced asset on every request.
                    .resourceChain(staticChainCache)
                    // Strategy for the version format: the md5 of the file's own bytes.
                    .addResolver(new VersionResourceResolver().addContentVersionStrategy("/**"));
        }

        // Everything else — root-level main.css, favicons, the loose *.jpg. NOT hashed, so it gets a short
        // period it can actually recover from, never "immutable".
        if (!registry.hasMappingForPattern("/**")) {
            registry.addResourceHandler("/**")
                    .addResourceLocations(CLASSPATH_RESOURCE_LOCATIONS)
                    .setCacheControl(CacheControl.maxAge(plainCacheSeconds, TimeUnit.SECONDS)
                            .cachePublic()
                            .mustRevalidate());
        }
    }

    /** {@code classpath:/static/} + {@code js} -> {@code classpath:/static/js/}. See the trap note above. */
    private static String[] locationsUnder(String dir) {
        String[] out = new String[CLASSPATH_RESOURCE_LOCATIONS.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = CLASSPATH_RESOURCE_LOCATIONS[i] + dir + "/";
        }
        return out;
    }

    /**
     * PERF-2 — the half of versioning that makes it reach the templates.
     *
     * Thymeleaf's {@code @{...}} link builder passes every URL through HttpServletResponse.encodeURL().
     * This filter wraps the response so that call asks ResourceUrlProvider for the hashed path. That is why
     * 228 {@code th:src}/{@code th:href} links across 88 templates needed ZERO edits for this slice.
     *
     * Boot normally registers this itself, under @ConditionalOnEnabledResourceChain inside
     * WebMvcAutoConfiguration — which @EnableWebMvc disables. Without this bean the resolver still SERVES
     * hashed URLs but nothing ever GENERATES them, and the whole slice silently degrades to a plain cache
     * period with no way to bust it. The gate asserts the rendered page carries hashed URLs for exactly
     * this reason.
     *
     * (ResourceUrlProvider and ResourceUrlProviderExposingInterceptor, which this filter reads, ARE defined
     * by WebMvcConfigurationSupport, so they survive @EnableWebMvc and need no help.)
     */
    @Bean
    public ResourceUrlEncodingFilter resourceUrlEncodingFilter() {
        return new ResourceUrlEncodingFilter();
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