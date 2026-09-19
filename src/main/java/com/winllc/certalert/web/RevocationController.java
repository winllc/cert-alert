package com.winllc.certalert.web;

import com.winllc.certalert.config.RevocationProperties;
import com.winllc.certalert.domain.RevocationStatus;
import com.winllc.certalert.repository.CachedCertificateRepository;
import com.winllc.certalert.revocation.IssuerCertificates;
import com.winllc.certalert.service.RevocationService;
import java.time.Duration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The revocation check: what it has found, and running it now.
 *
 * <p>Reading is open to anyone signed in - it is a count of what the cache holds. Running
 * it is not: it asks every authority in the directory at once, and it is a scheduled job
 * being brought forward rather than a page being refreshed.
 */
@RestController
@RequestMapping("/api/v1/revocation")
public class RevocationController {

    private final RevocationService revocationService;
    private final CachedCertificateRepository certificates;
    private final IssuerCertificates issuers;
    private final RevocationProperties properties;

    public RevocationController(
            RevocationService revocationService,
            CachedCertificateRepository certificates,
            IssuerCertificates issuers,
            RevocationProperties properties) {
        this.revocationService = revocationService;
        this.certificates = certificates;
        this.issuers = issuers;
        this.properties = properties;
    }

    /**
     * Where the check stands.
     *
     * @param issuerCertificates how many CA certificates are configured, which is what
     *     decides whether OCSP is possible and whether CRLs can be verified
     */
    public record Status(
            boolean enabled,
            String cron,
            int issuerCertificates,
            boolean ocspPossible,
            long revoked,
            long unknown,
            long notChecked,
            long good) {}

    /** What one run did. */
    public record RunResult(int entries, int certificatesChecked, int revoked, int unanswered, long durationMillis) {

        static RunResult of(RevocationService.Result result) {
            Duration took = result.duration();
            return new RunResult(
                    result.entries(), result.checked(), result.revoked(), result.unknown(), took.toMillis());
        }
    }

    @GetMapping
    public Status status() {
        return new Status(
                properties.isEnabled(),
                properties.getCron(),
                issuers.size(),
                !issuers.isEmpty(),
                certificates.countByRevocationStatus(RevocationStatus.REVOKED),
                certificates.countByRevocationStatus(RevocationStatus.UNKNOWN),
                certificates.countByRevocationStatus(RevocationStatus.NOT_CHECKED),
                certificates.countByRevocationStatus(RevocationStatus.GOOD));
    }

    @PostMapping("/check")
    public RunResult check() {
        return RunResult.of(revocationService.checkAll());
    }
}
