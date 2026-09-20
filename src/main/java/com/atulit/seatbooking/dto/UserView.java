package com.atulit.seatbooking.dto;

import com.atulit.seatbooking.domain.AppUser;
import com.atulit.seatbooking.domain.Role;

/** Note the absence of passwordHash. A DTO is also a way of not leaking things. */
public record UserView(Long id, String email, String displayName, Role role) {

    public static UserView of(AppUser user) {
        return new UserView(user.getId(), user.getEmail(), user.getDisplayName(), user.getRole());
    }
}
