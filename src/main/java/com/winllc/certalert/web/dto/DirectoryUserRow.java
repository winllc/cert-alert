package com.winllc.certalert.web.dto;

import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.DirectoryUser;
import java.time.Instant;

/**
 * One row of the users table.
 *
 * <p>Field names deliberately mirror the entity's property names: DataTables sends the
 * same name back as the column to search and order by, and the library resolves it as a
 * JPA attribute path.
 */
public record DirectoryUserRow(
        Long id,
        String dn,
        String uid,
        String commonName,
        String displayName,
        String givenName,
        String surname,
        String email,
        String telephoneNumber,
        String title,
        String employeeType,
        String country,
        String organization,
        String organizationalUnit,
        int certificateCount,
        CertificateStatus certificateStatus,
        Instant earliestExpiry,
        Instant latestExpiry,
        Instant lastSyncedAt) {

    public static DirectoryUserRow from(DirectoryUser user) {
        return new DirectoryUserRow(
                user.getId(),
                user.getDn(),
                user.getUid(),
                user.getCommonName(),
                user.getDisplayName(),
                user.getGivenName(),
                user.getSurname(),
                user.getEmail(),
                user.getTelephoneNumber(),
                user.getTitle(),
                user.getEmployeeType(),
                user.getCountry(),
                user.getOrganization(),
                user.getOrganizationalUnit(),
                user.getCertificateCount(),
                user.getCertificateStatus(),
                user.getEarliestExpiry(),
                user.getLatestExpiry(),
                user.getLastSyncedAt());
    }
}
