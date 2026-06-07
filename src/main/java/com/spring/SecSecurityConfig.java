package com.spring;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.authentication.rememberme.InMemoryTokenRepositoryImpl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationProvider;

import com.security.AuthServerAuthenticationProvider;
import com.security.CustomRememberMeServices;
import com.security.RevokeTokenLogoutHandler;
import com.security.TokenStore;
import com.security.google2fa.CustomAuthenticationProvider;
import com.security.google2fa.CustomWebAuthenticationDetailsSource;
import com.web.util.AuthServerClient;

import java.util.List;

@Configuration
@ComponentScan(basePackages = { "com.*" })
@EnableWebSecurity
@EnableMethodSecurity // Replaced legacy @EnableGlobalMethodSecurity
public class SecSecurityConfig {

    @Autowired
    private UserDetailsService userDetailsService;

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

    // 'local' = verify credentials against the local DB (legacy); 'server' = delegate to auth-service JWT.
    @Value("${auth.mode:local}")
    private String authMode;

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
            .csrf(csrf -> csrf.disable())
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
                    "/appointmentReq", "appointmentDashboard", "/services",
                    "/api/demo-request",
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
            .sessionManagement(session -> session
                .invalidSessionUrl("/invalidSession.html")
                .sessionFixation(fixation -> fixation.none())
                .maximumSessions(1)
                .sessionRegistry(sessionRegistry())
            )
            // .sessionManagement(session -> session
            //     .invalidSessionUrl("/invalidSession.html")
            //     .maximumSessions(1)
            //     .sessionRegistry(sessionRegistry())
            //     .and()
            //     .sessionFixation(fixation -> fixation.none())
            // )
            .logout(logout -> logout
                // Revoke the JWT at the auth-service before the session is torn down (server mode).
                .addLogoutHandler(revokeTokenLogoutHandler)
                .logoutSuccessHandler(myLogoutSuccessHandler)
                .invalidateHttpSession(false)
                .logoutSuccessUrl("/logout.html?logSucc=true")
                .deleteCookies("JSESSIONID")
                .permitAll()
            )
            .rememberMe(me -> me
                .rememberMeServices(rememberMeServices())
                .key("theKey")
            );

        return http.build();
    }

    // Bean Declarations

    @Bean
    public AuthenticationProvider authProvider() {
        // Phase 2: delegate to the auth-service (JWT IdP) when auth.mode=server; otherwise keep
        // verifying credentials against the local DB.
        if ("server".equalsIgnoreCase(authMode)) {
            return new AuthServerAuthenticationProvider(authServerClient, tokenStore);
        }
        final CustomAuthenticationProvider authProvider = new CustomAuthenticationProvider(userDetailsService);
        authProvider.setPasswordEncoder(encoder());
        return authProvider;
    }

    @Bean
    public PasswordEncoder encoder() {
        return new BCryptPasswordEncoder(11);
    }

    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    @Bean
    public RememberMeServices rememberMeServices() {
        return new CustomRememberMeServices("theKey", userDetailsService, new InMemoryTokenRepositoryImpl());
    }
}
