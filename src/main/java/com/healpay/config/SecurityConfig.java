package com.healpay.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Two roles:
 *  - VIEWER: can see dashboard, transactions, exceptions and ask the AI assistant.
 *  - ADMIN:  can additionally approve/reject fixes, run reconciliation and generate demo data.
 *
 * UI uses form login (with CSRF). The REST API under /api/** accepts HTTP Basic or the
 * browser session; CSRF is disabled for /api/** so Swagger UI and curl work.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/error", "/css/**", "/js/**", "/favicon.ico", "/actuator/health").permitAll()
                .requestMatchers(HttpMethod.POST,
                        "/api/exceptions/*/approve", "/api/exceptions/*/reject",
                        "/api/recon/run", "/api/simulator/**",
                        "/exceptions/*/approve", "/exceptions/*/reject",
                        "/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .formLogin(form -> form
                .loginPage("/login")
                .defaultSuccessUrl("/", true)
                .permitAll())
            .httpBasic(Customizer.withDefaults())
            .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(AppProperties props, PasswordEncoder encoder) {
        AppProperties.Users u = props.users();
        return new InMemoryUserDetailsManager(
                User.withUsername(u.adminUsername())
                        .password(encoder.encode(u.adminPassword()))
                        .roles("ADMIN", "VIEWER")
                        .build(),
                User.withUsername(u.viewerUsername())
                        .password(encoder.encode(u.viewerPassword()))
                        .roles("VIEWER")
                        .build());
    }
}
