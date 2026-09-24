package com.winllc.certalert.service;

import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.OwnerType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        // "credential(s)" for a person, because what is counted is the thing they have to
        // renew: a signing certificate and the key encipherment certificate issued with it
        // are one of these, and calling that two certificates reads as twice the work. A
        // server's certificate does both jobs, so there the two words mean the same thing
        // and the plainer one is better.
        String what = ownerType == OwnerType.USER ? "of your credential(s)" : "server certificate(s)";
        if (expired > 0 && expiring > 0) {
            return "%d %s expired, %d expiring soon".formatted(expired, what, expiring);
        }
        return expired > 0
                ? "%d %s expired".formatted(expired, what)
                : "%d %s expiring soon".formatted(expiring, what);
    }

    /** How many certificates the credentials in this round-up are made of, pairs counted. */
    public int getCertificateCount() {
        return entries.stream().mapToInt(Entry::getCertificateCount).sum();
    }

    /**
     * One expiring credential, and the entry in the directory it was published on.
     *
     * <p>A credential, not a certificate: a person's signing and key encipherment
     * certificates are issued together and renewed together, so they are one line with one
     * date - the date the first half of it stops working. Two lines saying the same thing
     * with different serial numbers reads as twice the work.
     */
    public static final class Entry {

        private final OwnerType ownerType;
        private final Long ownerId;
        private final String ownerName;
        private final String ownerDn;
        private final String subjectDn;
        private final String issuerDn;
        private final List<String> serialNumbers;
        private final String useSummary;
        private final int certificateCount;
        private final String keyAlgorithm;
        private final Integer keySize;
        private final String hashAlgorithm;
        private final Instant notAfter;
        private final long days;
        private final boolean expired;

        private Entry(
                OwnerType ownerType,
                AuditEvent.SubjectRef subject,
                Credential credential,
                Instant notAfter,
                long days,
                boolean expired) {

            CachedCertificate certificate = credential.first();
            this.ownerType = ownerType;
            this.ownerId = subject.id();
            this.ownerName = subject.name() == null ? subject.dn() : subject.name();
            this.ownerDn = subject.dn();
            this.subjectDn = certificate.getSubjectDn();
            this.issuerDn = certificate.getIssuerDn();
            this.serialNumbers = credential.certificates().stream()
                    .map(CachedCertificate::getSerialNumber)
                    .filter(Objects::nonNull)
                    .toList();
            this.useSummary = credential.useSummary();
            this.certificateCount = credential.certificates().size();
            this.keyAlgorithm = certificate.getKeyAlgorithm();
            this.keySize = certificate.getKeySize();
            this.hashAlgorithm = certificate.getHashAlgorithm();
            this.notAfter = notAfter;
            this.days = days;
            this.expired = expired;
        }

        static Entry of(
                Credential credential, AuditEvent.SubjectRef subject, OwnerType ownerType, Instant now) {

            // The date rather than the cached status: statuses are re-evaluated hourly, and
            // the round-up looks as far ahead as it is told to, which may be past the window
            // anything is marked EXPIRING_SOON in. For a pair it is the sooner of the two,
            // since that is when the person stops being able to do one of the two things.
            Instant notAfter = credential.expiresAt();
            boolean expired = !notAfter.isAfter(now);
            long days = ChronoUnit.DAYS.between(now, notAfter);
            return new Entry(ownerType, subject, credential, notAfter, days, expired);
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

        /** Both serials where this is a pair, so either half can be found in the directory. */
        public List<String> getSerialNumbers() {
            return serialNumbers;
        }

        public String getSerialNumber() {
            return serialNumbers.isEmpty() ? null : serialNumbers.getFirst();
        }

        /** "Signing and encryption" for a pair, or the one use, or empty where it says none. */
        public String getUseSummary() {
            return useSummary;
        }

        /** How many certificates this one credential is made of: two for a person's pair. */
        public int getCertificateCount() {
            return certificateCount;
        }

        public boolean isPair() {
            return certificateCount > 1;
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
            return "%s - %s%s - %s".formatted(
                    ownerName, subjectDn, isPair() ? " (signing and encryption)" : "", getStateSummary());
        }
    }
}
