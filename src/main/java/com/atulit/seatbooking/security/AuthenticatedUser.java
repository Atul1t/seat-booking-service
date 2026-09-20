package com.atulit.seatbooking.security;

import com.atulit.seatbooking.exception.ForbiddenException;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Pulls the user id out of a validated token.
 *
 * <p>The "sub" claim is the only place identity comes from. Nothing in a request body
 * is ever trusted to say who the caller is.
 */
public final class AuthenticatedUser {

    private AuthenticatedUser() {
    }

    public static Long idOf(Jwt jwt) {
        if (jwt == null) {
            throw new ForbiddenException("Authentication required");
        }
        return Long.valueOf(jwt.getSubject());
    }
}
