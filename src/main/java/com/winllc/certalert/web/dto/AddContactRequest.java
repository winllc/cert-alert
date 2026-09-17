package com.winllc.certalert.web.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

/**
 * Names a new point of contact, one way or the other.
 *
 * @param userId a person in the directory
 * @param email an address, which is linked to the person it belongs to if the directory
 *     knows exactly one such person
 */
public record AddContactRequest(Long userId, @Size(max = 320) String email) {

    @AssertTrue(message = "name either a directory user or an email address, not both")
    public boolean isExactlyOneTarget() {
        return (userId != null) ^ (email != null && !email.isBlank());
    }
}
