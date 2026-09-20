package com.atulit.seatbooking.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Stateless JWT security.
 *
 * <p>Three decisions worth understanding:
 *
 * <p><b>Sessions are disabled.</b> Every request carries its own proof of identity in an
 * Authorization header, so the server keeps no per-user state between requests. That is
 * what "stateless" means, and it is why this scales horizontally without sticky sessions
 * or a shared session store.
 *
 * <p><b>CSRF protection is off.</b> That is safe here precisely because authentication
 * rides in a header rather than a cookie. CSRF attacks work by tricking a browser into
 * sending a request with cookies it attaches automatically; a browser never attaches an
 * Authorization header on its own. Turn cookie auth back on and CSRF must come back with
 * it.
 *
 * <p><b>Browsing is public, booking is not.</b> Anyone can list shows and see the seat
 * map without an account, exactly like a real ticketing site. Holding and confirming
 * require a token.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // The client itself
                        .requestMatchers(HttpMethod.GET,
                                "/", "/index.html", "/*.css", "/*.js",
                                "/assets/**", "/favicon.ico").permitAll()

                        // Getting an account or a token cannot itself require a token
                        .requestMatchers("/api/auth/register", "/api/auth/login").permitAll()

                        // Browsing the catalogue is public
                        .requestMatchers(HttpMethod.GET, "/api/shows/**").permitAll()

                        // Everything that touches a seat or a booking needs a token.
                        .requestMatchers("/api/shows/*/holds").authenticated()
                        .requestMatchers("/api/holds/**").authenticated()
                        .requestMatchers("/api/bookings/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/shows").authenticated()

                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));

        return http.build();
    }

    /**
     * BCrypt deliberately takes tens of milliseconds per hash. That is imperceptible on
     * a login request and ruinous for anyone trying to brute-force a stolen password
     * table. It also salts each hash, so identical passwords produce different output
     * and a precomputed rainbow table is useless.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecretKey jwtSigningKey(@Value("${security.jwt.secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException(
                    "security.jwt.secret must be at least 32 bytes for HS256");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey key) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey key) {
        return NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }
}
