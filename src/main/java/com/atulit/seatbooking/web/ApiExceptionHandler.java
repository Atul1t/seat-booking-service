package com.atulit.seatbooking.web;

import com.atulit.seatbooking.dto.ApiError;
import com.atulit.seatbooking.exception.EmailAlreadyUsedException;
import com.atulit.seatbooking.exception.ForbiddenException;
import com.atulit.seatbooking.exception.HoldExpiredException;
import com.atulit.seatbooking.exception.InvalidCredentialsException;
import com.atulit.seatbooking.exception.HoldNotActiveException;
import com.atulit.seatbooking.exception.NotFoundException;
import com.atulit.seatbooking.exception.SeatUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * Maps domain failures onto HTTP status codes.
 *
 * <p>The distinction that matters here: 409 means "someone else got there first, the
 * request was well-formed", while 410 means "your hold timed out, start again". A client
 * can react differently to each, which it could not do if both came back as 400.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> onNotFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(404, "not_found", ex.getMessage()));
    }

    @ExceptionHandler(SeatUnavailableException.class)
    public ResponseEntity<ApiError> onSeatUnavailable(SeatUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError(409, "seat_unavailable", ex.getMessage(), ex.getSeats()));
    }

    @ExceptionHandler(HoldNotActiveException.class)
    public ResponseEntity<ApiError> onHoldNotActive(HoldNotActiveException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(409, "hold_not_active", ex.getMessage()));
    }

    @ExceptionHandler(HoldExpiredException.class)
    public ResponseEntity<ApiError> onHoldExpired(HoldExpiredException ex) {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(ApiError.of(410, "hold_expired", ex.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiError> onForbidden(ForbiddenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of(403, "forbidden", ex.getMessage()));
    }

    @ExceptionHandler(EmailAlreadyUsedException.class)
    public ResponseEntity<ApiError> onEmailTaken(EmailAlreadyUsedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(409, "email_already_used", ex.getMessage()));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiError> onBadCredentials(InvalidCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError.of(401, "invalid_credentials", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> onIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(400, "bad_request", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> onValidationFailure(MethodArgumentNotValidException ex) {
        List<String> problems = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(400, "validation_failed",
                        "Request body is invalid", problems));
    }
}
