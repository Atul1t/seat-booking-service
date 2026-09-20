package com.atulit.seatbooking.security;

import com.atulit.seatbooking.domain.AppUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Issues signed tokens.
 *
 * <p>A JWT is three base64 segments: header, claims, signature. The first two are merely
 * encoded, not encrypted — anyone holding a token can read the claims. So a token may
 * carry identity, never secrets.
 *
 * <p>What makes it trustworthy is the signature: HMAC-SHA256 over the first two segments
 * using a key only this server knows. Change a single character of the claims and the
 * signature no longer matches, so the server can verify a token it issued without
 * looking anything up in a database. That is the whole point — and also the catch: a
 * token cannot be revoked before it expires, which is why the lifetime is short.
 */
@Service
public class JwtService {

    private final JwtEncoder encoder;
    private final Clock clock;
    private final Duration tokenTtl;

    public JwtService(JwtEncoder encoder,
                      Clock clock,
                      @Value("${security.jwt.ttl}") Duration tokenTtl) {
        this.encoder = encoder;
        this.clock = clock;
        this.tokenTtl = tokenTtl;
    }

    public IssuedToken issueFor(AppUser user) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(tokenTtl);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("seat-booking-service")
                .issuedAt(now)
                .expiresAt(expiresAt)
                // "sub" is the standard claim for who the token is about.
                .subject(String.valueOf(user.getId()))
                .claim("email", user.getEmail())
                .claim("name", user.getDisplayName())
                .claim("role", user.getRole().name())
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        return new IssuedToken(value, expiresAt);
    }

    public record IssuedToken(String value, Instant expiresAt) {
    }
}
