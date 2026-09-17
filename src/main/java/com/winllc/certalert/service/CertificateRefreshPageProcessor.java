package com.winllc.certalert.service;

import com.winllc.certalert.alert.AlertDispatcher;
import com.winllc.certalert.alert.CertificateAlert;
import com.winllc.certalert.domain.AuditAction;
import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.domain.OwnerType;
import com.winllc.certalert.repository.CachedCertificateRepository;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-evaluates one page of cached certificates in its own transaction.
 *
 * <p>Separate from {@link CertificateRefreshService} because the transaction has to be
 * per page: the orchestrating loop calls in through the proxy, which a method calling
 * itself would bypass, and one long transaction over the whole table would hold locks for
 * the duration of the job.
 */
@Service
public class CertificateRefreshPageProcessor {

    private final CachedCertificateRepository certificateRepository;
    private final DirectoryUserRepository userRepository;
    private final DirectoryServerRepository serverRepository;
    private final CertificateStatusEvaluator evaluator;
    private final AlertDispatcher alertDispatcher;
    private final AuditService auditService;

    public CertificateRefreshPageProcessor(
            CachedCertificateRepository certificateRepository,
            DirectoryUserRepository userRepository,
            DirectoryServerRepository serverRepository,
            CertificateStatusEvaluator evaluator,
            AlertDispatcher alertDispatcher,
            AuditService auditService) {
        this.certificateRepository = certificateRepository;
        this.userRepository = userRepository;
        this.serverRepository = serverRepository;
        this.evaluator = evaluator;
        this.alertDispatcher = alertDispatcher;
        this.auditService = auditService;
    }

    /**
     * Refreshes the next page of possibly-stale certificates.
     *
     * @param afterId keyset cursor; pages stay stable as rows are updated underneath
     */
    @Transactional
    public RefreshPage refreshPage(Instant horizon, long afterId, Instant now, int pageSize) {
        List<CachedCertificate> stale = certificateRepository.findStale(
                CertificateStatus.EXPIRED, horizon, afterId, PageRequest.of(0, pageSize));
        if (stale.isEmpty()) {
            return new RefreshPage(0, 0, afterId);
        }

        List<Long> changedIds = new ArrayList<>();
        List<AuditEvent> records = new ArrayList<>();
        String actor = AuditActors.current(AuditActors.REFRESH);
        int alerts = 0;
        for (CachedCertificate certificate : stale) {
            CertificateStatus previous = certificate.getStatus();
            CertificateStatus current = evaluator.evaluate(certificate.getNotAfter(), now);
            if (current == previous) {
                continue;
            }
            certificate.updateStatus(current, now);
            changedIds.add(certificate.getId());
            records.add(statusChange(certificate, previous, current, now, actor));
            if (current.isAlertable()) {
                alerts += raiseAlert(certificate, current, now);
            }
        }
        auditService.recordAll(records);

        refreshOwnerSummaries(changedIds);
        return new RefreshPage(stale.size(), alerts, stale.getLast().getId());
    }

    /**
     * The other way a certificate's state changes: not because the directory published
     * something different, but because time passed. It reads the same in the history.
     */
    private AuditEvent statusChange(
            CachedCertificate certificate,
            CertificateStatus previous,
            CertificateStatus current,
            Instant now,
            String actor) {

        DirectoryUser user = certificate.getUser();
        AuditEvent.SubjectRef subject = user != null
                ? AuditEvent.SubjectRef.of(user)
                : AuditEvent.SubjectRef.of(certificate.getServer());
        return AuditEvent.about(
                        subject,
                        AuditAction.CERTIFICATE_STATUS_CHANGED,
                        "The certificate for %s went from %s to %s"
                                .formatted(certificate.getSubjectDn(), previous, current),
                        now)
                .by(actor)
                .forCertificate(certificate.getSha256Fingerprint());
    }

    private int raiseAlert(CachedCertificate certificate, CertificateStatus status, Instant now) {
        long daysUntilExpiry = evaluator.daysUntilExpiry(certificate.getNotAfter(), now);
        DirectoryUser user = certificate.getUser();
        if (user != null) {
            alertDispatcher.dispatch(CertificateAlert.from(
                    OwnerType.USER,
                    user.getId(),
                    nameOf(user),
                    user.getDn(),
                    user.getEmail(),
                    certificate,
                    evaluator.severityFor(status, daysUntilExpiry),
                    daysUntilExpiry,
                    now));
            return 1;
        }
        DirectoryServer server = certificate.getServer();
        if (server != null) {
            alertDispatcher.dispatch(CertificateAlert.from(
                    OwnerType.SERVER,
                    server.getId(),
                    server.getCommonName() == null ? server.getDn() : server.getCommonName(),
                    server.getDn(),
                    server.getServerPocDisplay(),
                    certificate,
                    evaluator.severityFor(status, daysUntilExpiry),
                    daysUntilExpiry,
                    now));
            return 1;
        }
        return 0;
    }

    /** The entity roll-up columns are what the tables filter on, so they move too. */
    private void refreshOwnerSummaries(List<Long> changedCertificateIds) {
        if (changedCertificateIds.isEmpty()) {
            return;
        }
        certificateRepository.findUserIdsByCertificateIds(changedCertificateIds).forEach(id ->
                userRepository.findWithCertificatesById(id).ifPresent(user -> {
                    user.refreshCertificateSummary();
                    userRepository.save(user);
                }));
        certificateRepository.findServerIdsByCertificateIds(changedCertificateIds).forEach(id ->
                serverRepository.findWithCertificatesById(id).ifPresent(server -> {
                    server.refreshCertificateSummary();
                    serverRepository.save(server);
                }));
    }

    private String nameOf(DirectoryUser user) {
        if (user.getDisplayName() != null) {
            return user.getDisplayName();
        }
        return user.getCommonName() == null ? user.getDn() : user.getCommonName();
    }

    /** @param lastId highest id examined, the cursor for the next page */
    public record RefreshPage(int examined, int alerts, long lastId) {}
}
