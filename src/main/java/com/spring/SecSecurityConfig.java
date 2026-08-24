package com.spring;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;

import org.springframework.security.authentication.AuthenticationProvider;

import com.security.AuthServerAuthenticationProvider;
import com.security.CsrfCookieFilter;
import com.security.RevokeTokenLogoutHandler;
import com.security.SessionRegistryLogoutHandler;
import com.security.TokenStore;
import com.security.google2fa.CustomWebAuthenticationDetailsSource;
import com.web.util.AuthServerClient;

import java.util.List;

@Configuration
@ComponentScan(basePackages = { "com.*" })
@EnableWebSecurity
@EnableMethodSecurity // Replaced legacy @EnableGlobalMethodSecurity
public class SecSecurityConfig {

    @Autowired
    private AuthenticationSuccessHandler myAuthenticationSuccessHandler;

    @Autowired
    private LogoutSuccessHandler myLogoutSuccessHandler;

    @Autowired
    private AuthenticationFailureHandler authenticationFailureHandler;

    @Autowired
    private CustomWebAuthenticationDetailsSource authenticationDetailsSource;

    @Autowired
    private AuthServerClient authServerClient;

    @Autowired
    private TokenStore tokenStore;

    @Autowired
    private RevokeTokenLogoutHandler revokeTokenLogoutHandler;

    public SecSecurityConfig() {
        super();
    }

    @Bean
    public AuthenticationManager authenticationManager() {
        return new ProviderManager(List.of(authProvider()));
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // CSRF ON for the session-based monolith. Token in a JS-readable XSRF-TOKEN cookie
            // (CsrfCookieFilter materializes it; header.html $.ajaxSetup echoes X-XSRF-TOKEN).
            // Public, pre-session POST endpoints are exempt (they're anonymous; the reset ones are
            // already token-credentialed by the auth-service).
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .ignoringRequestMatchers(
                    "/login",
                    "/user/registration*", "/user/registrationCaptcha*", "/old/user/registration*",
                    "/user/resetPassword*", "/user/savePassword*", "/user/resendRegistrationToken*",
                    "/addDonation", "/appointmentReq", "/registerHospital*", "/api/demo-request",
                    "/loadDoctorsByHospital", "/loadDoctorDetails",
                    "/storefront/**"   // public storefront guest checkout (slice 47, anonymous — no CSRF token)
                )
            )
            .authorizeHttpRequests(auth -> auth
                // Static Resource Rules (Migrated from WebSecurity ignoring block)
                .requestMatchers(
                    "/css/**", "/js/**", "/images/**", "/webjars/**", "/static/**", 
                    "/bootstrap/**", "/jQExp/**", "/main.css", "/*.png", "/*.ico", "/*.jpeg"
                ).permitAll()
                // Public Endpoint Rules
                .requestMatchers(
                    "/", "/home*", "/login*", "/logout*", "/signin/**", "/signup/**",
                    "/customLogin", "/user/registration*", "/registrationConfirm*",
                    "/expiredAccount*", "/registration*", "/registerHospital*",
                    // "appointmentDashboard" was HERE, and is deliberately gone rather than corrected.
                    //
                    // Spring Security warns that it is missing a leading slash, and the inviting fix is to add
                    // one. That would be a security regression: a pattern without the slash matches NOTHING, so
                    // /appointmentDashboard has been requiring a login all along — by accident. Adding the slash
                    // would make it genuinely public.
                    //
                    // It is a MODULE DASHBOARD, not a public page: its own javadoc says "its own dashboard, like
                    // education/business — users with userType=APPOINTMENT land here via the success handler",
                    // and no sibling dashboard (business, education, welfare, agriculture) is permitted here.
                    // Nothing anonymous links to it. So the entry was wrong, not its spelling.
                    "/appointmentReq", "/services",
                    "/api/demo-request",
                    "/api/live-users",   // public "users online" badge on the landing/login headers
                    "/store", "/storefront/**",
                    "/appointment", "/islamicChannels*", "/loadDoctorsByHospital",
                    "/loadDoctorDetails", "/addDonation", "/badUser*",
                    "/user/resendRegistrationToken*", "/forgetPassword*",
                    "/user/resetPassword*", "/user/changePassword*", "/user/savePassword*",
                    "/emailError*",
                    "/old/user/registration*", "/successRegister*", "/qrcode*", "/invalidSession*"
                ).permitAll()
                // Privileged Endpoint Rules — logged-in "change my password" still requires the privilege.
                // (The forgot/reset flow is token-gated by the auth-service, so it is permitAll above.)
                .requestMatchers(
                    "/user/updatePassword*"
                ).hasAuthority("CHANGE_PASSWORD_PRIVILEGE")
                // Secure fallbacks
                .anyRequest().hasAuthority("LOGIN_PRIVILEGE")
            )
            .formLogin(login -> login
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .defaultSuccessUrl("/home")
                .failureUrl("/login?error=true")
                .successHandler(myAuthenticationSuccessHandler)
                .failureHandler(authenticationFailureHandler)
                .authenticationDetailsSource(authenticationDetailsSource)
                .permitAll()
            )
            // Session policy — the "banks and Google" model: a user may be signed in on as many devices as
            // they like, and security comes from VISIBILITY over those sessions, not from a hard cap.
            //
            // WAS: maximumSessions(1) + sessionFixation.none(). Two problems, both real:
            //
            //  1. A cap of 1 is wrong for this product. The same account legitimately runs on a till, a
            //     back-office PC and a phone. Worse, whichever way the cap resolves is a bad outcome: refuse
            //     the new login and a crashed browser LOCKS THE USER OUT of their own account until the old
            //     session times out; expire the old one and they are silently kicked off mid-work. And it
            //     buys little security — an attacker holding valid credentials simply logs in and boots the
            //     real user off. Visibility + revoke is the control that actually helps.
            //
            //  2. sessionFixation.none() DISABLED session-fixation protection: the session id was not
            //     rotated on login, so an id planted in a victim's browser before they authenticate stays
            //     valid afterwards. Restored to changeSessionId (the Spring default).
            //
            // maximumSessions(-1) = UNLIMITED, but keep the .maximumSessions(...).sessionRegistry(...) pair:
            // that is what installs RegisterSessionAuthenticationStrategy, and therefore what keeps the
            // registry populated. Dropping the block entirely would silently empty the SessionRegistry and
            // take the "users online" badge (and any future active-sessions screen) with it.
            .sessionManagement(session -> session
                .invalidSessionUrl("/invalidSession.html")
                .sessionFixation(fixation -> fixation.changeSessionId())
                .maximumSessions(-1)
                .sessionRegistry(sessionRegistry())
            )
            // (A commented-out duplicate of the OLD single-session block lived here. Deleted rather than
            //  left in place: it now contradicts the live policy above, and a reader finding two session
            //  blocks — one capped, one not — has no way to tell which is intended.)
            .logout(logout -> logout
                // Revoke the JWT at the auth-service before the session is torn down (server mode).
                .addLogoutHandler(revokeTokenLogoutHandler)
                // invalidateHttpSession(false) below means no HttpSessionDestroyedEvent fires on
                // logout, so the registry must be told explicitly or the user stays "online".
                .addLogoutHandler(sessionRegistryLogoutHandler())
                .logoutSuccessHandler(myLogoutSuccessHandler)
                .invalidateHttpSession(false)
                .logoutSuccessUrl("/logout.html?logSucc=true")
                .deleteCookies("JSESSIONID")
                .permitAll()
            )
            .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class);

        return http.build();
    }

    // Bean Declarations

    @Bean
    public AuthenticationProvider authProvider() {
        // Single identity provider: credentials are verified by the auth-service (JWT IdP).
        // The legacy local-DB provider was removed with the auth/user-store decommission.
        // Captcha is verified by the auth-service too — the token is forwarded, not checked here.
        return new AuthServerAuthenticationProvider(authServerClient, tokenStore);
    }

    @Bean
    public PasswordEncoder encoder() {
        return new BCryptPasswordEncoder(11);
    }

    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /**
     * Declared here rather than component-scanned: constructor-injecting the SessionRegistry into a
     * {@code @Component} would make that bean depend on this config class while this class is still
     * being populated. Taking it from {@link #sessionRegistry()} keeps the wiring one-directional,
     * matching how the registry is passed to sessionManagement above.
     */
    @Bean
    public SessionRegistryLogoutHandler sessionRegistryLogoutHandler() {
        return new SessionRegistryLogoutHandler(sessionRegistry());
    }

    /**
     * SessionRegistryImpl only drops a session when it receives an HttpSessionDestroyedEvent, and
     * Spring publishes that event only if this bean exists. Without it the registry keeps every
     * session that ever logged in — so the "users online" count on the landing page would climb
     * forever and never decay on logout or timeout.
     */
    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

}
