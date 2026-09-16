package com.winllc.certalert.repository;

import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.DirectoryEntry;
import com.winllc.certalert.domain.DirectoryServer;
import jakarta.persistence.criteria.Join;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/**
 * The extra filters the search tables layer on top of DataTables' own column search.
 *
 * <p>These all read the denormalised roll-up on the entity row, which is what makes them
 * cheap. Because the roll-up takes the worst state of an entity's certificates, "expired"
 * means "holds at least one expired certificate" and "not expired" means "holds
 * certificates, none of them expired".
 */
public final class DirectorySpecifications {

    private DirectorySpecifications() {}

    /** Matches everything; the identity to build a filter chain on. */
    public static <T> Specification<T> unfiltered() {
        return (root, query, builder) -> null;
    }

    /** Matches nothing, for a filter whose subject does not exist. */
    public static <T> Specification<T> matchNothing() {
        return (root, query, builder) -> builder.disjunction();
    }

    public static <T extends DirectoryEntry> Specification<T> certificateStatusIn(
            Collection<CertificateStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return unfiltered();
        }
        return (root, query, builder) -> root.get("certificateStatus").in(statuses);
    }

    /** Entities holding at least one expired certificate, or none at all. */
    public static <T extends DirectoryEntry> Specification<T> expired(boolean expired) {
        if (expired) {
            return (root, query, builder) -> builder.equal(root.get("certificateStatus"), CertificateStatus.EXPIRED);
        }
        return (root, query, builder) -> root.get("certificateStatus")
                .in(CertificateStatus.VALID, CertificateStatus.EXPIRING_SOON);
    }

    /** Entities whose next certificate to expire does so inside the given window. */
    public static <T extends DirectoryEntry> Specification<T> expiringWithinDays(int days, Instant now) {
        Instant cutoff = now.plus(days, ChronoUnit.DAYS);
        return (root, query, builder) -> builder.between(root.get("earliestExpiry"), now, cutoff);
    }

    public static <T extends DirectoryEntry> Specification<T> hasCertificates(boolean has) {
        return (root, query, builder) -> has
                ? builder.greaterThan(root.get("certificateCount"), 0)
                : builder.equal(root.get("certificateCount"), 0);
    }

    /**
     * Servers whose {@code serverPOC} holds this value - one half of the join between a
     * person and the servers they are responsible for.
     *
     * <p>Contacts are stored lowercased and the value is lowercased here too, so the match
     * never depends on how the directory cased either side. No {@code distinct} is needed:
     * contacts are a set, so a server matches a given value at most once, and the count
     * query the search table issues alongside this stays accurate.
     */
    public static Specification<DirectoryServer> pointOfContact(String value) {
        if (value == null || value.isBlank()) {
            return unfiltered();
        }
        return pointOfContactAnyOf(List.of(value));
    }

    /**
     * Servers whose {@code serverPOC} matches any of these values.
     *
     * <p>This is how a person is resolved to their servers. The FSD schema defines
     * {@code serverPOC} as the <em>name</em> of the responsible person or organization, but
     * directories in practice put an address there instead, so a person is matched against
     * every value that could name them: each of their addresses and each form of their
     * name. See {@code DirectoryUser.identifiers}.
     */
    public static Specification<DirectoryServer> pointOfContactAnyOf(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return unfiltered();
        }
        List<String> normalised = values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
        if (normalised.isEmpty()) {
            return unfiltered();
        }
        return (root, query, builder) -> {
            Join<DirectoryServer, String> contacts = root.join("serverPocs");
            return builder.lower(contacts).in(normalised);
        };
    }
}
