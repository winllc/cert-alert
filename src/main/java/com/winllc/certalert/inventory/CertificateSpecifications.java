package com.winllc.certalert.inventory;

import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.CertificateRisk;
import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.domain.OwnerType;
import com.winllc.certalert.domain.Project;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/**
 * A {@link CertificateFilter} as a query over the cached certificates.
 *
 * <p>The owner is joined (left, since a certificate hangs off a person or a server) for the
 * text search, and fetched on the same join when the query is for rows rather than a count,
 * so a page of certificates is one query and not one per owner.
 */
final class CertificateSpecifications {

    private CertificateSpecifications() {}

    /**
     * @param risksShown whether risk analysis is licensed; without it the risk filter is ignored
     * @param projectsShown whether projects are licensed; without them the project filter is ignored
     */
    static Specification<CachedCertificate> of(
            CertificateFilter filter, Instant now, boolean risksShown, boolean projectsShown, boolean fetchOwners) {
        return (root, query, builder) -> {
            boolean counting = query.getResultType() == Long.class || query.getResultType() == long.class;
            Join<CachedCertificate, DirectoryUser> user;
            Join<CachedCertificate, DirectoryServer> server;
            if (fetchOwners && !counting) {
                @SuppressWarnings("unchecked")
                Join<CachedCertificate, DirectoryUser> fetchedUser =
                        (Join<CachedCertificate, DirectoryUser>) root.<CachedCertificate, DirectoryUser>fetch("user", JoinType.LEFT);
                @SuppressWarnings("unchecked")
                Join<CachedCertificate, DirectoryServer> fetchedServer =
                        (Join<CachedCertificate, DirectoryServer>) root.<CachedCertificate, DirectoryServer>fetch("server", JoinType.LEFT);
                user = fetchedUser;
                server = fetchedServer;
            } else {
                user = root.join("user", JoinType.LEFT);
                server = root.join("server", JoinType.LEFT);
            }

            List<Predicate> parts = new ArrayList<>();
            String q = contains(filter.q());
            if (q != null) {
                parts.add(builder.or(
                        like(builder, root.get("subjectDn"), q),
                        like(builder, root.get("issuerDn"), q),
                        like(builder, root.get("serialNumber"), q),
                        like(builder, root.get("sha256Fingerprint"), q),
                        like(builder, root.get("subjectAlternativeNames"), q),
                        like(builder, user.get("displayName"), q),
                        like(builder, user.get("commonName"), q),
                        like(builder, user.get("uid"), q),
                        like(builder, user.get("email"), q),
                        like(builder, server.get("commonName"), q),
                        like(builder, server.get("uid"), q),
                        like(builder, server.get("serverUrl"), q)));
            }
            if (filter.status() != null && !filter.status().isEmpty()) {
                parts.add(root.get("status").in(filter.status()));
            }
            if (filter.ownerType() == OwnerType.USER) {
                parts.add(builder.isNotNull(root.get("user")));
            } else if (filter.ownerType() == OwnerType.SERVER) {
                parts.add(builder.isNotNull(root.get("server")));
            }
            if (filter.expiresWithinDays() != null && filter.expiresWithinDays() > 0) {
                parts.add(builder.between(
                        root.get("notAfter"), now, now.plus(filter.expiresWithinDays(), ChronoUnit.DAYS)));
            }
            if (filter.expiresFrom() != null) {
                parts.add(builder.greaterThanOrEqualTo(
                        root.get("notAfter"), filter.expiresFrom().atStartOfDay(ZoneOffset.UTC).toInstant()));
            }
            if (filter.expiresTo() != null) {
                parts.add(builder.lessThan(
                        root.get("notAfter"), filter.expiresTo().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
            }
            String issuer = contains(filter.issuer());
            if (issuer != null) {
                parts.add(like(builder, root.get("issuerDn"), issuer));
            }
            List<String> keyAlgorithms = lowered(filter.keyAlgorithm());
            if (!keyAlgorithms.isEmpty()) {
                parts.add(builder.lower(root.get("keyAlgorithm")).in(keyAlgorithms));
            }
            if (filter.keySizeMin() != null) {
                parts.add(builder.greaterThanOrEqualTo(root.get("keySize"), filter.keySizeMin()));
            }
            if (filter.keySizeMax() != null) {
                parts.add(builder.lessThanOrEqualTo(root.get("keySize"), filter.keySizeMax()));
            }
            List<String> hashes = lowered(filter.hashAlgorithm());
            if (!hashes.isEmpty()) {
                parts.add(builder.lower(root.get("hashAlgorithm")).in(hashes));
            }
            if (filter.revocation() != null && !filter.revocation().isEmpty()) {
                parts.add(root.get("revocationStatus").in(filter.revocation()));
            }
            if (risksShown && filter.risk() != null && !filter.risk().isBlank()) {
                if (filter.risk().equalsIgnoreCase("any")) {
                    parts.add(builder.isNotNull(root.get("riskFlags")));
                } else {
                    CertificateRisk risk;
                    try {
                        risk = CertificateRisk.valueOf(filter.risk().trim().toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("'%s' is not a kind of certificate risk".formatted(filter.risk()));
                    }
                    parts.add(builder.like(root.get("riskFlags"), "%" + risk.name() + "%"));
                }
            }
            if (filter.serverId() != null) {
                parts.add(builder.equal(root.get("server").get("id"), filter.serverId()));
            }
            if (filter.userId() != null) {
                parts.add(builder.equal(root.get("user").get("id"), filter.userId()));
            }
            if (projectsShown && filter.projectId() != null) {
                parts.add(builder.or(
                        inProject(root, query, builder, filter.projectId(), "servers", "server"),
                        inProject(root, query, builder, filter.projectId(), "members", "user")));
            }
            return parts.isEmpty() ? null : builder.and(parts.toArray(new Predicate[0]));
        };
    }

    /** An exists, so a certificate is one row however its owner came to be in the project. */
    private static Predicate inProject(
            Root<CachedCertificate> root,
            jakarta.persistence.criteria.CommonAbstractCriteria query,
            CriteriaBuilder builder,
            Long projectId,
            String collection,
            String owner) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<Project> project = subquery.from(Project.class);
        Join<Project, Object> members = project.join(collection);
        return builder.exists(subquery
                .select(builder.literal(1))
                .where(builder.equal(project.get("id"), projectId), builder.equal(members, root.get(owner))));
    }

    private static Predicate like(CriteriaBuilder builder, Expression<String> value, String pattern) {
        return builder.like(builder.lower(value), pattern, '\\');
    }

    /** A contains pattern with the wildcards in the term itself taken literally. */
    private static String contains(String term) {
        if (term == null || term.isBlank()) {
            return null;
        }
        String escaped = term.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private static List<String> lowered(List<String> values) {
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
