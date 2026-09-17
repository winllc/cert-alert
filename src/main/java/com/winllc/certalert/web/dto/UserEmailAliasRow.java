package com.winllc.certalert.web.dto;

import com.winllc.certalert.domain.UserEmailAlias;
import java.time.Instant;

/**
 * One address a person answers to beyond the directory's own.
 *
 * @param kind PERSONAL, or GROUP for a list several people are on
 * @param sharedWith how many other people answer to it, which for a list is the point
 */
public record UserEmailAliasRow(
        Long id,
        String address,
        UserEmailAlias.Kind kind,
        String label,
        int sharedWith,
        String addedBy,
        Instant addedAt) {

    public static UserEmailAliasRow from(UserEmailAlias alias, int sharedWith) {
        return new UserEmailAliasRow(
                alias.getId(),
                alias.getAddress(),
                alias.getKind(),
                alias.getLabel(),
                sharedWith,
                alias.getAddedBy(),
                alias.getAddedAt());
    }
}
