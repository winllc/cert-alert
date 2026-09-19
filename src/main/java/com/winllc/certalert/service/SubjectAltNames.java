package com.winllc.certalert.service;

import com.winllc.certalert.domain.CertificateRisk;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads the names a certificate is good for, and says what is worrying about them.
 *
 * <p>A pure function of the names and two thresholds: no directory, no database, nothing to
 * mock. The judgements it makes are the ones somebody would make by eye - a wildcard covers
 * hosts that do not exist yet, a name with no domain means something different on every
 * network, forty names means one key for forty things - and they are flags rather than
 * alerts because none of them is a fault in itself.
 */
public final class SubjectAltNames {

    /**
     * Suffixes under which a wildcard is covering other organizations rather than one. Not a
     * public suffix list - that is a downloaded, changing thing and this runs where nothing
     * can be downloaded - but the shape of the check is the same: a wildcard whose remainder
     * is one of these, or is a single label, is too high up.
     */
    private static final Set<String> PUBLIC_SUFFIXES =
            Set.of("gov", "mil", "com", "net", "org", "edu", "int", "ic.gov", "co.uk", "org.uk", "gov.uk");

    private SubjectAltNames() {}

    /**
     * What a certificate names, and what is worrying about it.
     *
     * @param count how many names there are, which is not the same as {@code names.size()}
     *     when the stored list was truncated
     * @param risks what is worrying, or empty where nothing is
     */
    public record Assessment(int count, Set<CertificateRisk> risks) {

        public static final Assessment NONE = new Assessment(0, Set.of());

        public boolean isRisky() {
            return !risks.isEmpty();
        }

        /** The flags as they are stored: names, comma separated, or null for none. */
        public String flags() {
            return risks.isEmpty()
                    ? null
                    : risks.stream().map(Enum::name).sorted().reduce((a, b) -> a + "," + b).orElse(null);
        }
    }

    public static Assessment assess(Collection<String> names, int maxNames, int maxDomains) {
        List<String> cleaned = names == null
                ? List.of()
                : names.stream()
                        .filter(name -> name != null && !name.isBlank())
                        .map(name -> name.trim().toLowerCase(Locale.ROOT))
                        .distinct()
                        .toList();
        if (cleaned.isEmpty()) {
            return Assessment.NONE;
        }

        Set<CertificateRisk> risks = EnumSet.noneOf(CertificateRisk.class);
        Set<String> domains = new LinkedHashSet<>();

        for (String name : cleaned) {
            boolean wildcard = name.startsWith("*.") || name.equals("*");
            if (wildcard) {
                risks.add(CertificateRisk.WILDCARD);
                if (isBroad(name)) {
                    risks.add(CertificateRisk.BROAD_WILDCARD);
                }
            } else if (!name.contains(".")) {
                // A bare "*" is a wildcard, not a hostname with the domain left off.
                risks.add(CertificateRisk.BARE_HOSTNAME);
            }
            domains.add(registrableDomain(name));
        }

        if (cleaned.size() > maxNames) {
            risks.add(CertificateRisk.MANY_NAMES);
        }
        if (domains.size() > maxDomains) {
            risks.add(CertificateRisk.MANY_DOMAINS);
        }
        return new Assessment(cleaned.size(), Set.copyOf(risks));
    }

    /** Parses the stored list back out: the names, comma separated, possibly truncated. */
    public static List<String> parse(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        return List.of(stored.split(",")).stream()
                .map(String::trim)
                .filter(name -> !name.isEmpty() && !name.equals("..."))
                .toList();
    }

    /**
     * Whether a wildcard reaches past one organization: a bare {@code *}, a wildcard over a
     * single label, or one over a suffix everybody shares.
     */
    private static boolean isBroad(String name) {
        if (name.equals("*") || name.equals("*.")) {
            return true;
        }
        String remainder = name.substring(2);
        return !remainder.contains(".") || PUBLIC_SUFFIXES.contains(remainder);
    }

    /**
     * The last two labels, which stands in for the registrable domain. Wrong for the
     * handful of two-part suffixes it does not know, and right often enough to count how
     * many different things a certificate is for.
     */
    private static String registrableDomain(String name) {
        String bare = name.startsWith("*.") ? name.substring(2) : name;
        String[] labels = bare.split("\\.");
        if (labels.length <= 2) {
            return bare;
        }
        String candidate = labels[labels.length - 2] + "." + labels[labels.length - 1];
        // One more label where the last two are a suffix everybody shares - ic.gov, co.uk.
        if (PUBLIC_SUFFIXES.contains(candidate) && labels.length >= 3) {
            return labels[labels.length - 3] + "." + candidate;
        }
        return candidate;
    }
}
