package com.atulit.seatbooking.exception;

/**
 * Wrong email or wrong password.
 *
 * <p>Deliberately one exception for both cases. Distinguishing them would tell an
 * attacker which email addresses are registered, which is a free user-enumeration
 * oracle.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Email or password is incorrect");
    }
}
