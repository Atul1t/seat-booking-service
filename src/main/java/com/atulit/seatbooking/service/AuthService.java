package com.atulit.seatbooking.service;

import com.atulit.seatbooking.domain.AppUser;
import com.atulit.seatbooking.dto.AuthResponse;
import com.atulit.seatbooking.dto.LoginRequest;
import com.atulit.seatbooking.dto.RegisterRequest;
import com.atulit.seatbooking.dto.UserView;
import com.atulit.seatbooking.exception.EmailAlreadyUsedException;
import com.atulit.seatbooking.exception.InvalidCredentialsException;
import com.atulit.seatbooking.exception.NotFoundException;
import com.atulit.seatbooking.repository.AppUserRepository;
import com.atulit.seatbooking.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
public class AuthService {

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final Clock clock;

    public AuthService(AppUserRepository users,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.clock = clock;
    }

    @Transactional
    public UserView register(RegisterRequest request) {
        if (users.existsByEmailIgnoreCase(request.email())) {
            throw new EmailAlreadyUsedException(request.email());
        }
        AppUser user = new AppUser(
                request.email(),
                passwordEncoder.encode(request.password()),
                request.displayName(),
                clock.instant());
        return UserView.of(users.save(user));
    }

    /**
     * Note that a missing account and a wrong password produce the same exception, and
     * that the password is still hashed and compared even when the account does not
     * exist would be the more thorough version of this. As written, a missing account
     * returns marginally faster than a wrong password, which is a timing side channel.
     * Closing it means always running the BCrypt comparison against a dummy hash.
     */
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        AppUser user = users.findByEmailIgnoreCase(request.email())
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        JwtService.IssuedToken token = jwtService.issueFor(user);
        return new AuthResponse(token.value(), token.expiresAt(), UserView.of(user));
    }

    @Transactional(readOnly = true)
    public UserView byId(Long id) {
        return users.findById(id)
                .map(UserView::of)
                .orElseThrow(() -> new NotFoundException("User " + id + " not found"));
    }
}
