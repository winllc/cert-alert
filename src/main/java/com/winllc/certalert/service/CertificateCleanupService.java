package com.winllc.certalert.service;

import com.winllc.certalert.domain.OwnerType;
import com.winllc.certalert.domain.RevocationStatus;
import com.winllc.certalert.ldap.LdapProperties;
import com.winllc.certalert.repository.CachedCertificateRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes from the directory the certificates it should not still be publishing.
 *
 * <p>Two kinds. A certificate whose validity ran out months ago is untidy: it clutters the
 * entry, it is counted in every report, and nothing will ever use it again. A certificate an
 * authority has revoked is worse than untidy - it is still published, and something that
 * reads the directory and does not check revocation will pick it up and use it. That is the
 * one this exists for; the expired ones come along because the same modify removes them.
 *
 * <p><strong>Off by default, and deliberately harder to turn on than the prune.</strong>
 * Everything else in this application reads the directory and keeps an index of it. This
 * writes, and what it writes cannot be undone from here: the value is gone from somebody
 * else's system of record, and the only copy of it was the one that was deleted.
 *
 * <p>So: grace periods on both sides, long enough that a skewed clock or a late renewal
 * cannot make a certificate eligible; a cap on how many one run will remove, because a
 * mistake caught after five hundred is a different kind of morning from one caught after
 * fifty thousand; a preview that counts what would go without touching anything; and an
 * audit record for every certificate removed, naming which and why.
 */
@Service
public class CertificateCleanupService {

    private static final Logger log = LoggerFactory.getLogger(CertificateCleanupService.class);

    private final CachedCertificateRepository certificates;
    private final CertificateCleanupProcessor processor;
    private final LdapProperties properties;
    private final SyncRunRecorder runRecorder;
    private final Clock clock;

    public CertificateCleanupService(
            CachedCertificateRepository certificates,
            CertificateCleanupProcessor processor,
            LdapProperties properties,
            SyncRunRecorder runRecorder,
            Clock clock) {
        this.certificates = certificates;
        this.processor = processor;
        this.properties = properties;
        this.runRecorder = runRecorder;
        this.clock = clock;
    }

    /** What one run did. */
    public record Result(
            int removed, int entries, int alreadyGone, int refused, int missing, Duration duration) {

        static final Result NOTHING = new Result(0, 0, 0, 0, 0, Duration.ZERO);
    }

    /**
     * What a run would remove, without removing it.
     *
     * @param expired how many have been finished with for longer than the window
     * @param revoked how many an authority has revoked
     * @param wouldRemove what the next run would actually take, which is the cap applied
     *     to the two above
     */
    public record Preview(boolean enabled, long expired, long revoked, long wouldRemove, int maxPerRun) {}

    @Transactional(readOnly = true)
    public Preview preview() {
        LdapProperties.CertificateCleanup settings = properties.getCertificateCleanup();
        Instant now = Instant.now(clock);
        long expired = settings.isRemoveExpired()
                ? certificates.countExpiredBefore(now.minus(settings.getExpiredAfter()))
                : 0;
        long revoked = settings.isRemoveRevoked()
                ? certificates.countRevokedBefore(
                        RevocationStatus.REVOKED, now.minus(settings.getRevokedAfter()))
                : 0;
        // An overestimate where a certificate is both, which is the honest direction for a
        // number somebody is reading before switching this on.
        return new Preview(
                settings.isEnabled(),
                expired,
                revoked,
                Math.min(expired + revoked, settings.getMaxPerRun()),
                settings.getMaxPerRun());
    }

    /** Removes what the windows say is finished with, up to the cap. */
    public Result run() {
        LdapProperties.CertificateCleanup settings = properties.getCertificateCleanup();
        if (!settings.isEnabled()) {
            return Result.NOTHING;
        }
        Instant now = Instant.now(clock);
        long startedNanos = System.nanoTime();
        String actor = AuditActors.current(AuditActors.CLEANUP);
        Long runId = runRecorder.started(com.winllc.certalert.domain.SyncJob.CERTIFICATE_CLEANUP, now);

        int removed = 0;
        int alreadyGone = 0;
        int refused = 0;
        int missing = 0;
        int entriesTouched = 0;
        try {
            Map<Owner, List<Long>> byOwner = candidates(settings, now);
            for (Map.Entry<Owner, List<Long>> owner : byOwner.entrySet()) {
                CertificateCleanupProcessor.Outcome outcome =
                        processor.clean(owner.getKey().type(), owner.getKey().id(), owner.getValue(), actor, now);
                removed += outcome.removed();
                alreadyGone += outcome.alreadyGone();
                if (outcome.refused()) {
                    refused++;
                } else if (outcome.missing()) {
                    missing++;
                } else if (outcome.removed() > 0 || outcome.alreadyGone() > 0) {
                    entriesTouched++;
                }
            }
        } catch (RuntimeException e) {
            runRecorder.failed(runId, Instant.now(clock), e.toString());
            log.error("Certificate cleanup failed", e);
            throw e;
        }

        Result result = new Result(
                removed, entriesTouched, alreadyGone, refused, missing,
                Duration.ofNanos(System.nanoTime() - startedNanos));
        runRecorder.finished(runId, Instant.now(clock), new DirectorySyncResult(
                com.winllc.certalert.domain.SyncJob.CERTIFICATE_CLEANUP,
                entriesTouched, 0, 0, removed, 0, 0, refused, result.duration()));
        log.info("Certificate cleanup finished in {} ms: {} removed from {} entr(ies), "
                        + "{} already gone, {} refused by the directory, {} entr(ies) no longer there",
                result.duration().toMillis(), removed, entriesTouched, alreadyGone, refused, missing);
        return result;
    }

    /**
     * What to remove, grouped by the entry that publishes it.
     *
     * <p>Grouped because the unit of work is an entry: one search and one modify take away
     * all of that entry's finished certificates at once, and doing it per certificate would
     * mean rewriting the same entry twice for a pair that expired together.
     */
    private Map<Owner, List<Long>> candidates(LdapProperties.CertificateCleanup settings, Instant now) {
        PageRequest cap = PageRequest.of(0, settings.getMaxPerRun());
        List<CachedCertificateRepository.CleanupCandidate> found = new ArrayList<>();
        if (settings.isRemoveRevoked()) {
            // Revoked first: the cap is a cap, and if it binds these are the ones that
            // matter - an expired certificate is harmless and a revoked one is not.
            found.addAll(certificates.findRevokedBefore(
                    RevocationStatus.REVOKED, now.minus(settings.getRevokedAfter()), cap));
        }
        if (settings.isRemoveExpired() && found.size() < settings.getMaxPerRun()) {
            found.addAll(certificates.findExpiredBefore(now.minus(settings.getExpiredAfter()), cap));
        }

        Map<Owner, List<Long>> byOwner = new LinkedHashMap<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (CachedCertificateRepository.CleanupCandidate candidate : found) {
            // A certificate can be both expired and revoked, and both queries found it.
            if (seen.size() >= settings.getMaxPerRun() || !seen.add(candidate.getId())) {
                continue;
            }
            Owner owner = ownerOf(candidate);
            if (owner == null) {
                continue;
            }
            byOwner.computeIfAbsent(owner, key -> new ArrayList<>()).add(candidate.getId());
        }
        return byOwner;
    }

    private static Owner ownerOf(CachedCertificateRepository.CleanupCandidate candidate) {
        if (candidate.getUserId() != null) {
            return new Owner(OwnerType.USER, candidate.getUserId());
        }
        return candidate.getServerId() == null ? null : new Owner(OwnerType.SERVER, candidate.getServerId());
    }

    /** An entry, as the two repositories between them know it. */
    public record Owner(OwnerType type, Long id) {}
}
