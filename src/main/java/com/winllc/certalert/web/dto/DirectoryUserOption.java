package com.winllc.certalert.web.dto;

import com.winllc.certalert.domain.DirectoryUser;

/** A person as the contact picker shows them: enough to recognise, and the id to send back. */
public record DirectoryUserOption(Long id, String displayName, String email, String uid, String dutyOrganization) {

    public static DirectoryUserOption from(DirectoryUser user) {
        return new DirectoryUserOption(
                user.getId(),
                user.getDisplayName() != null ? user.getDisplayName() : user.getCommonName(),
                user.getEmail(),
                user.getUid(),
                user.getDutyOrganization());
    }
}
