package com.atulit.seatbooking.exception;

/** Registration with an email that is already taken. Maps to HTTP 409. */
public class EmailAlreadyUsedException extends RuntimeException {

    public EmailAlreadyUsedException(String email) {
        super("An account already exists for " + email);
    }
}
