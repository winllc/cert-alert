package com.winllc.certalert.license;

/**
 * Every CertAlert feature in the ENS licensing portal's catalog (products-page
 * {@code src/data/licensing.ts}), by the id a license file lists it under, as
 * {@code certalert.<id>}.
 *
 * <p>The license screen lists all of them, ticked or not. What each one gates is decided
 * where the gated thing is: {@link RequiresFeature} on its endpoints, {@link Licensing} in
 * its jobs, menus and buttons. Ids must never be renamed: licenses carrying them have been
 * issued.
 */
public enum LicensedFeature {

    DIRECTORY_SYNC("directory-sync", "Directory sweeps",
            "Finds every certificate people and servers publish in the directory.", false),
    CHANGELOG_SYNC("changelog-sync", "Changelog sync",
            "Picks up directory changes within minutes from the changelog, between full sweeps.", false),
    NOTIFICATIONS("notifications", "Email notifications",
            "Warns points of contact by email, with digests and email alerts.", false),
    METRICS("metrics", "Metrics",
            "Trends across the whole directory: expiry, issuing authorities, revocation.", false),
    PROJECTS("projects", "Projects",
            "Groups servers into projects with their own administrators.", false),
    REVOCATION_CHECKS("revocation-checks", "Revocation checks",
            "Checks every certificate against OCSP and CRLs.", false),
    ENDPOINT_CHECKS("endpoint-checks", "Endpoint checks",
            "Probes servers for the certificate they actually serve.", false),
    RISKY_NAMES("risky-names", "Risk analysis",
            "Flags certificates with risky names, keys and algorithms.", false),
    DIRECTORY_CLEANUP("directory-cleanup", "Directory cleanup",
            "Removes expired certificates and stale entries from the directory.", false),
    CLASSIFICATION_BANNERS("classification-banners", "Classification banners",
            "Configurable banners for classified networks.", false),
    MULTI_DIRECTORY("multi-directory", "Multiple directories",
            "Sweeps more than one directory or forest from one installation.", true);

    private final String id;
    private final String label;
    private final String description;
    private final boolean enterprise;

    LicensedFeature(String id, String label, String description, boolean enterprise) {
        this.id = id;
        this.label = label;
        this.description = description;
        this.enterprise = enterprise;
    }

    /** The id after {@code certalert.} in a license file. */
    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /** Only in the top size band, evaluations and pilots, or when ENS adds it to an order. */
    public boolean enterprise() {
        return enterprise;
    }

    /** The feature with this id, or the id itself as the label when the catalog has no such feature. */
    public static String labelOf(String id) {
        for (LicensedFeature feature : values()) {
            if (feature.id.equals(id)) {
                return feature.label;
            }
        }
        return id;
    }
}
