package com.winllc.certalert.web.dto;

import com.winllc.certalert.domain.ServerContact;
import java.time.Instant;

/**
 * One point of contact managed here.
 *
 * @param id what to send back to remove it
 * @param label the person's name, or the address where it is not a person
 * @param address where an alert about this server would be sent, if anywhere
 * @param userId the person in the directory, when the contact is one
 * @param userDn their entry, so the row can link through to it
 * @param addedBy who added it
 * @param addedAt when
 */
public record ServerContactRow(
        Long id, String label, String address, Long userId, String userDn, String addedBy, Instant addedAt) {

    public static ServerContactRow from(ServerContact contact) {
        return new ServerContactRow(
                contact.getId(),
                contact.label(),
                contact.address(),
                contact.getUser() == null ? null : contact.getUser().getId(),
                contact.getUser() == null ? null : contact.getUser().getDn(),
                contact.getAddedBy(),
                contact.getAddedAt());
    }
}
