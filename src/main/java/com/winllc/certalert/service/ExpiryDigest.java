package com.winllc.certalert.service;

import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.OwnerType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One round-up email's worth of expiring certificates: what the email templates read.
 *
 * <p>A round-up is split by what the certificates belong to before it is rendered. A
 * person's own certificate is theirs to renew; a server's is theirs to chase, possibly on
 * somebody else's behalf, and the page to go to is a different one. Those are two different
 * messages, so they get two templates and, where somebody has both, two emails - rather
 * than one list that quietly mixes "yours" with "one you look after".
 *
 * <p>Plain getters rather than record accessors: these are read from templates.
 */
public final class ExpiryDigest {

    private final OwnerType ownerType;
    private final String recipientName;
    private final List<Entry> entries;
    private final int expired;
    private final int expiring;

    private ExpiryDigest(OwnerType ownerType, String recipientName, List<Entry> entries) {
        this.ownerType = ownerType;
        this.recipientName = recipientName;
        this.entries = List.copyOf(entries);
        this.expired = (int) entries.stream().filter(Entry::isExpired).count();
        this.expiring = entries.size() - this.expired;
    }

    /**
     * Splits one person's round-up into the messages it is actually made of, their own
     * certificates first.
     */
    public static List<ExpiryDigest> split(String recipientName, List<Entry> entries) {
        Map<OwnerType, List<Entry>> byOwner = new LinkedHashMap<>();
        byOwner.put(OwnerType.USER, new ArrayList<>());
        byOwner.put(OwnerType.SERVER, new ArrayList<>());
        entries.forEach(entry -> byOwner.get(entry.getOwnerType()).add(entry));

        List<ExpiryDigest> digests = new ArrayList<>(2);
        byOwner.forEach((ownerType, owned) -> {
            if (!owned.isEmpty()) {
                digests.add(new ExpiryDigest(ownerType, recipientName, owned));
            }
        });
        return List.copyOf(digests);
    }

    public OwnerType getOwnerType() {
        return ownerType;
    }

    /** What to call the person, or null for an address nobody has claimed. */
    public String getRecipientName() {
        return recipientName;
    }

    public List<Entry> getEntries() {
        return entries;
    }

    public int getExpired() {
        return expired;
    }

    public int getExpiring() {
        return expiring;
    }

    public int getTotal() {
        return entries.size();
    }

    /**
     * The subject line, and the sentence the email opens with.
     *
     * <p>Counted rather than conjugated: "certificate(s)" is how the rest of cert-alert
     * words this, and it means the sentence reads the same whether one is expiring or
     * forty.
     */
    public String getHeadline() {
        String what = ownerType == OwnerType.USER ? "of your certificate(s)" : "server certificate(s)";
        if (expired > 0 && expiring > 0) {
            return "%d %s expired, %d expiring soon".formatted(expired, what, expiring);
        }
        return expired > 0
                ? "%d %s expired".formatted(expired, what)
                : "%d %s expiring soon".formatted(expiring, what);
    }

    /** One expiring certificate, and the entry in the directory it was published on. */
    public static final class Entry {

        private final OwnerType ownerType;
        private final Long ownerId;
        private final String ownerName;
        private final String ownerDn;
        private final String subjectDn;
        private final String issuerDn;
        private final String serialNumber;
        private final String keyAlgorithm;
        private final Integer keySize;
        private final String hashAlgorithm;
        private final Instant notAfter;
        private final long days;
        private final boolean expired;

        private Entry(
                OwnerType ownerType,
                AuditEvent.SubjectRef subject,
                CachedCertificate certificate,
                long days,
                boolean expired) {

            this.ownerType = ownerType;
            this.ownerId = subject.id();
            this.ownerName = subject.name() == null ? subject.dn() : subject.name();
            this.ownerDn = subject.dn();
            this.subjectDn = certificate.getSubjectDn();
            this.issuerDn = certificate.getIssuerDn();
            this.serialNumber = certificate.getSerialNumber();
            this.keyAlgorithm = certificate.getKeyAlgorithm();
            this.keySize = certificate.getKeySize();
            this.hashAlgorithm = certificate.getHashAlgorithm();
            this.notAfter = certificate.getNotAfter();
            this.days = days;
            this.expired = expired;
        }

        public static Entry of(
                CachedCertificate certificate, AuditEvent.SubjectRef subject, OwnerType ownerType, Instant now) {

            boolean expired = certificate.getStatus() == CertificateStatus.EXPIRED;
            long days = ChronoUnit.DAYS.between(now, certificate.getNotAfter());
            return new Entry(ownerType, subject, certificate, days, expired);
        }

        public OwnerType getOwnerType() {
            return ownerType;
        }

        /** The directory entry, so a template can link to the page for it. */
        public Long getOwnerId() {
            return ownerId;
        }

        public String getOwnerName() {
            return ownerName;
        }

        public String getOwnerDn() {
            return ownerDn;
        }

        public String getSubjectDn() {
            return subjectDn;
        }

        public String getIssuerDn() {
            return issuerDn;
        }

        public String getSerialNumber() {
            return serialNumber;
        }

        public String getHashAlgorithm() {
            return hashAlgorithm;
        }

        public Instant getNotAfter() {
            return notAfter;
        }

        /** Negative once it has expired; templates say which with {@link #isExpired()}. */
        public long getDays() {
            return days;
        }

        /** Days either side of today, as a count to print. */
        public long getDaysAbsolute() {
            return Math.abs(days);
        }

        public boolean isExpired() {
            return expired;
        }

        /** The expiry date alone. An {@code Instant} prints ISO-8601 in UTC. */
        public String getDay() {
            return notAfter == null ? "" : notAfter.toString().substring(0, 10);
        }

        /** "RSA 2048", or just the algorithm where the size was not readable. */
        public String getKeyDescription() {
            if (keyAlgorithm == null) {
                return "";
            }
            return keySize == null ? keyAlgorithm : keyAlgorithm + " " + keySize;
        }

        public String getStateSummary() {
            return expired
                    ? "expired %d day(s) ago, on %s".formatted(Math.abs(days), getDay())
                    : "expires in %d day(s), on %s".formatted(days, getDay());
        }

        /** The one-line form, as it reads on the notifications page. */
        public String getSummary() {
            return "%s - %s - %s".formatted(ownerName, subjectDn, getStateSummary());
        }
    }
}
