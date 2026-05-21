package com.spring;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.builders.WebSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.authentication.rememberme.InMemoryTokenRepositoryImpl;

import com.security.CustomRememberMeServices;
import com.security.google2fa.CustomAuthenticationProvider;
import com.security.google2fa.CustomWebAuthenticationDetailsSource;

@Configuration
@ComponentScan(basePackages = { "com.*" })
// @ImportResource({ "classpath:webSecurityConfig.xml" })
@EnableWebSecurity
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class SecSecurityConfig extends WebSecurityConfigurerAdapter {

    @Autowired
    private UserDetailsService userDetailsService;

    @Autowired
    private AuthenticationSuccessHandler myAuthenticationSuccessHandler;

    @Autowired
    private LogoutSuccessHandler myLogoutSuccessHandler;

//    @Autowired
    private AuthenticationFailureHandler authenticationFailureHandler;

    @Autowired
    private CustomWebAuthenticationDetailsSource authenticationDetailsSource;

    public SecSecurityConfig() {
        super();
    }

    
    @Bean
    @Override
    public AuthenticationManager authenticationManagerBean() throws Exception {
        return super.authenticationManagerBean();
    }
    
    @Override
    protected void configure(final AuthenticationManagerBuilder auth) throws Exception {
        auth.authenticationProvider(authProvider());
    }

   @Override
    public void configure(final WebSecurity web) throws Exception {
        web.ignoring().antMatchers(
            "/css/**",
            "/js/**",
            "/images/**",
            "/webjars/**",
            "/static/**",
            "/bootstrap/**",      // ✅ added
            "/jQExp/**",          // ✅ added
            "/main.css",          // ✅ added
            "/*.png",             // ✅ added
            "/*.ico",             // ✅ added
            "/*.jpeg"             // ✅ added
        );
    }

    @Override
    protected void configure(final HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeRequests(requests -> requests
                        .antMatchers("/css/**", "/js/**", "/images/**", "/bootstrap/**",
                                "/jQExp/**", "/webjars/**", "/static/**", "/main.css").permitAll()
                        .antMatchers("/", "/home*", "/login*", "/logout*", "/signin/**", "/signup/**",
                                "/customLogin", "/user/registration*", "/registrationConfirm*",
                                "/expiredAccount*", "/registration*", "/registerHospital*",
                                "/appointmentReq", "appointmentDashboard", "/services",
                                "/appointment", "/islamicChannels*", "/loadDoctorsByHospital",
                                "/loadDoctorDetails", "/addDonation", "/badUser*",
                                "/user/resendRegistrationToken*", "/forgetPassword*",
                                "/user/resetPassword*", "/user/changePassword*", "/emailError*",
                                "/static/**", "/old/user/registration*", "/successRegister*",
                                "/qrcode*").permitAll()
                        .antMatchers("/invalidSession*").permitAll()  // ? removed anonymous()
                        .antMatchers("/user/updatePassword*", "/user/savePassword*",
                                "/updatePassword*").hasAuthority("CHANGE_PASSWORD_PRIVILEGE")
                        .anyRequest().hasAuthority("LOGIN_PRIVILEGE"))
                .formLogin(login -> login
                        .loginPage("/login")                    // GET /login ? shows login page
                        .loginProcessingUrl("/login")           // POST /login ? processes credentials
                        .defaultSuccessUrl("/home")
                        .failureUrl("/login?error=true")
                        .successHandler(myAuthenticationSuccessHandler)
                        .failureHandler(authenticationFailureHandler)
                        .authenticationDetailsSource(authenticationDetailsSource)
                        .permitAll())
                .sessionManagement(management -> management
                        .invalidSessionUrl("/invalidSession.html")
                        .maximumSessions(1).sessionRegistry(sessionRegistry()).and()
                        .sessionFixation().none())
                .logout(logout -> logout
                        .logoutSuccessHandler(myLogoutSuccessHandler)
                        .invalidateHttpSession(false)
                        .logoutSuccessUrl("/logout.html?logSucc=true")
                        .deleteCookies("JSESSIONID")
                        .permitAll())
                .rememberMe(me -> me.rememberMeServices(rememberMeServices()).key("theKey"));
    }

    // beans

    @Bean
    public DaoAuthenticationProvider authProvider() {
        final CustomAuthenticationProvider authProvider = new CustomAuthenticationProvider();
        authProvider.setUserDetailsService(userDetailsService);
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
        CustomRememberMeServices rememberMeServices = new CustomRememberMeServices("theKey", userDetailsService, new InMemoryTokenRepositoryImpl());
        return rememberMeServices;
    }
}