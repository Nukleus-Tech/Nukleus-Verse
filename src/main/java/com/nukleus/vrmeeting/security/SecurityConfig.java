
package com.nukleus.vrmeeting.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import org.springframework.security.config.http.SessionCreationPolicy;

@Configuration
public class SecurityConfig {


    @Autowired
    private JwtFilter jwtFilter;



    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http
    ) throws Exception {


        http
            .csrf(csrf -> csrf.disable())


            .cors(cors -> {})


            .sessionManagement(session ->
                    session.sessionCreationPolicy(
                            SessionCreationPolicy.STATELESS
                    )
            )


            .authorizeHttpRequests(auth -> auth

                    // Public API

                    .requestMatchers(
                            "/api/admin/login"
                    )
                    .permitAll()


                    // Admin APIs

                    .requestMatchers(
                            "/api/admin/**"
                    )
                    .authenticated()


                    .anyRequest()
                    .permitAll()
            )


            .addFilterBefore(
                    jwtFilter,
                    UsernamePasswordAuthenticationFilter.class
            );


        return http.build();

    }

}