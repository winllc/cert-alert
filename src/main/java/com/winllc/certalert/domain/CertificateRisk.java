package com.winllc.certalert.domain;

/**
 * What is worrying about the names a certificate is good for.
 *
 * <p>A certificate's subject alternative names say what it may be used for, and the risk is
 * always the same shape: the more they cover, and the less precisely they say what, the more
 * one stolen key is worth. None of these is a fault in itself - a wildcard is a legitimate
 * thing to issue - which is why they are flags to look at rather than alerts to send.
 */
public enum CertificateRisk {

    /**
     * A wildcard: one key for every name under a domain. Whoever holds it can be any host
     * that does not exist yet, and nobody can tell from the certificate which hosts it is
     * actually installed on.
     */
    WILDCARD("Wildcard", "One key answers for every name under a domain"),

    /**
     * A wildcard high enough up to cover other people's hosts - {@code *.gov}, or a bare
     * {@code *}. A directory should not be publishing one.
     */
    BROAD_WILDCARD("Broad wildcard", "Covers a whole suffix, not one organization's hosts"),

    /** More names than a server has any business answering for. */
    MANY_NAMES("Many names", "Covers more names than the threshold allows"),

    /**
     * Names spread across unrelated domains. A certificate for one service says what it is
     * for; one covering six domains says only that somebody batched a renewal.
     */
    MANY_DOMAINS("Many domains", "Spans unrelated domains, so what it is for is unclear"),

    /**
     * A name with no domain at all - {@code web01}, {@code localhost}. What it resolves to
     * depends on whose search list is in play, which is the definition of ambiguous.
     */
    BARE_HOSTNAME("Bare hostname", "A name with no domain resolves differently for everybody");

    private final String label;
    private final String why;

    CertificateRisk(String label, String why) {
        this.label = label;
        this.why = why;
    }

    public String label() {
        return label;
    }

    /** One line saying why it is on this list, for the page that shows the flag. */
    public String why() {
        return why;
    }

    /** Whether this one is bad enough to read as a fault rather than a thing to know. */
    public boolean isSevere() {
        return this == BROAD_WILDCARD;
    }
}
