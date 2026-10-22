package com.winllc.certalert.inventory;

import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.OwnerType;
import com.winllc.certalert.domain.RevocationStatus;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * What the certificate inventory is narrowed to: the same query parameters for the page's
 * table, the API's list, an export and a saved view, so "export what I am looking at" is
 * exactly that.
 *
 * <p>Every field is optional; together they are an AND. Lists are an OR within the field.
 *
 * @param q text found in the subject, issuer, serial number, fingerprint or owner's name
 * @param status the certificate's own state
 * @param ownerType people's certificates or servers'
 * @param expiresWithinDays from now until this many days ahead
 * @param expiresFrom on or after the start of this day (UTC)
 * @param expiresTo on or before the end of this day (UTC)
 * @param issuer text found in the issuer's DN
 * @param keyAlgorithm RSA, EC, Ed25519 ... (exact, any case)
 * @param keySizeMin key size in bits, at least
 * @param keySizeMax key size in bits, at most
 * @param hashAlgorithm SHA-256, SHA-1 ... (exact, any case)
 * @param revocation what the issuer says
 * @param risk any, or one flag's name - where risk analysis is licensed
 * @param projectId in this project - where projects are licensed
 * @param serverId held by this server
 * @param userId held by this person
 */
public record CertificateFilter(
        String q,
        List<CertificateStatus> status,
        OwnerType ownerType,
        Integer expiresWithinDays,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiresFrom,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiresTo,
        String issuer,
        List<String> keyAlgorithm,
        Integer keySizeMin,
        Integer keySizeMax,
        List<String> hashAlgorithm,
        List<RevocationStatus> revocation,
        String risk,
        Long projectId,
        Long serverId,
        Long userId) {

    public static final CertificateFilter NONE =
            new CertificateFilter(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

    /** What it narrows to, for the audit trail and a report's covering note: only what is set. */
    public Map<String, Object> describe() {
        Map<String, Object> set = new LinkedHashMap<>();
        put(set, "q", q);
        put(set, "status", status);
        put(set, "ownerType", ownerType);
        put(set, "expiresWithinDays", expiresWithinDays);
        put(set, "expiresFrom", expiresFrom);
        put(set, "expiresTo", expiresTo);
        put(set, "issuer", issuer);
        put(set, "keyAlgorithm", keyAlgorithm);
        put(set, "keySizeMin", keySizeMin);
        put(set, "keySizeMax", keySizeMax);
        put(set, "hashAlgorithm", hashAlgorithm);
        put(set, "revocation", revocation);
        put(set, "risk", risk);
        put(set, "projectId", projectId);
        put(set, "serverId", serverId);
        put(set, "userId", userId);
        return set;
    }

    private static void put(Map<String, Object> map, String key, Object value) {
        if (value == null || (value instanceof String text && text.isBlank())
                || (value instanceof List<?> list && list.isEmpty())) {
            return;
        }
        map.put(key, value instanceof List<?> list ? new ArrayList<>(list.stream().map(String::valueOf).toList()) : value);
    }
}
