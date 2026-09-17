package com.winllc.certalert.repository;

import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.DirectoryEntry;
import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.ServerContact;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
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
     * Servers whose contacts include any of these values, from either place a contact comes
     * from: the directory's {@code serverPOC}, and the contacts managed here.
     *
     * <p>This is how a person is resolved to their servers. The FSD schema defines
     * {@code serverPOC} as the <em>name</em> of the responsible person or organization, but
     * directories in practice put an address there instead, so a person is matched against
     * every value that could name them: each of their addresses and each form of their
     * name. See {@code DirectoryUser.identifiers}.
     */
    public static Specification<DirectoryServer> pointOfContactAnyOf(Collection<String> values) {
        List<String> normalised = normalise(values);
        if (normalised.isEmpty()) {
            return unfiltered();
        }
        return (root, query, builder) -> builder.or(
                scrapedContactIn(root, query, builder, normalised),
                managedContactIn(root, query, builder, normalised, null));
    }

    /**
     * The servers a particular person is the point of contact for.
     *
     * <p>Their identifiers match a directory contact or an address somebody typed; their id
     * matches a managed contact that was linked to them, which is the only match that stays
     * right when they are renamed or their address changes.
     */
    public static Specification<DirectoryServer> pointOfContactOf(Long userId, Collection<String> identifiers) {
        List<String> normalised = normalise(identifiers);
        if (userId == null && normalised.isEmpty()) {
            return matchNothing();
        }
        return (root, query, builder) -> builder.or(
                normalised.isEmpty()
                        ? builder.disjunction()
                        : scrapedContactIn(root, query, builder, normalised),
                managedContactIn(root, query, builder, normalised, userId));
    }

    /**
     * Matching through an {@code exists} rather than a join, because a server can hold both
     * a directory contact and a managed one naming the same person. A join would return
     * that server once per match, which shows a duplicate row and inflates the count the
     * search table renders beside it.
     */
    private static Predicate scrapedContactIn(
            Root<DirectoryServer> root, CriteriaQuery<?> query, CriteriaBuilder builder, List<String> values) {

        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<DirectoryServer> server = subquery.from(DirectoryServer.class);
        Join<DirectoryServer, String> contacts = server.join("serverPocs");
        return builder.exists(subquery
                .select(builder.literal(1))
                .where(builder.equal(server, root), builder.lower(contacts).in(values)));
    }

    /** The same over the contacts managed here: by address, by the person linked, or both. */
    private static Predicate managedContactIn(
            Root<DirectoryServer> root,
            CriteriaQuery<?> query,
            CriteriaBuilder builder,
            List<String> values,
            Long userId) {

        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<ServerContact> contact = subquery.from(ServerContact.class);

        List<Predicate> matches = new java.util.ArrayList<>();
        if (!values.isEmpty()) {
            matches.add(builder.lower(contact.get("email")).in(values));
        }
        if (userId != null) {
            matches.add(builder.equal(contact.get("user").get("id"), userId));
        }
        if (matches.isEmpty()) {
            return builder.disjunction();
        }
        return builder.exists(subquery
                .select(builder.literal(1))
                .where(
                        builder.equal(contact.get("server"), root),
                        builder.or(matches.toArray(new Predicate[0]))));
    }

    private static List<String> normalise(Collection<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }
}
