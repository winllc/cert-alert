package com.winllc.certalert.service;

import com.winllc.certalert.domain.AuditAction;
import com.winllc.certalert.domain.CertificateRisk;
import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.RevocationStatus;
import com.winllc.certalert.repository.AuditEventRepository;
import com.winllc.certalert.repository.CachedCertificateRepository;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.repository.MetricsRepository;
import com.winllc.certalert.repository.NotificationRepository;
import com.winllc.certalert.repository.ProjectRepository;
import com.winllc.certalert.web.dto.Metrics;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The numbers behind the metrics page.
 *
 * <p>Every one of them is a grouped count done by the database. The point of the page is to
 * be able to ask it of a directory of a hundred thousand entries, which rules out reading
 * the certificates and counting them here.
 */
@Service
public class MetricsService {

    /** How far back and forward the issued-against-expiring series runs. */
    private static final int MONTHS_BACK = 12;

    private static final int MONTHS_FORWARD = 12;

    /** How many rows a distribution shows before it stops being a summary. */
    private static final int TOP_VALUES = 10;

    private final MetricsRepository metrics;
    private final CachedCertificateRepository certificates;
    private final DirectoryUserRepository users;
    private final DirectoryServerRepository servers;
    private final NotificationRepository notifications;
    private final AuditEventRepository audit;
    private final ProjectRepository projects;
    private final Clock clock;

    public MetricsService(
            MetricsRepository metrics,
            CachedCertificateRepository certificates,
            DirectoryUserRepository users,
            DirectoryServerRepository servers,
            NotificationRepository notifications,
            AuditEventRepository audit,
            ProjectRepository projects,
            Clock clock) {
        this.metrics = metrics;
        this.certificates = certificates;
        this.users = users;
        this.servers = servers;
        this.notifications = notifications;
        this.audit = audit;
        this.projects = projects;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Metrics gather() {
        Instant now = Instant.now(clock);

        return new Metrics(
                certificateCounts(),
                entryCounts(),
                keyUsage(),
                hashUsage(),
                signatureUsage(),
                expiryWindows(now),
                monthlySeries(now),
                notificationCounts(),
                riskCounts(),
                issuance(now),
                revocation(now),
                attributes(),
                projects.count());
    }

    /**
     * How many certificates carry each kind of risky name. A certificate can be more than
     * one kind, so these do not add up to "any" and are not meant to.
     */
    private Map<String, Long> riskCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("any", metrics.countByRiskFlagsIsNotNull());
        for (CertificateRisk risk : CertificateRisk.values()) {
            counts.put(risk.name(), metrics.countByRisk(risk.name()));
        }
        return counts;
    }

    /**
     * What has been issued lately, for how long, and by whom.
     *
     * <p>The average validity is taken over the last year rather than over everything: a
     * directory holds certificates issued under policies nobody remembers, and averaging
     * those in hides the thing worth seeing, which is what the policy is now.
     */
    private Metrics.Issuance issuance(Instant now) {
        Double averageSeconds = metrics.averageValiditySeconds(now.minus(365, ChronoUnit.DAYS));
        return new Metrics.Issuance(
                metrics.countIssuedBetween(now.minus(30, ChronoUnit.DAYS), now),
                metrics.countIssuedBetween(now.minus(90, ChronoUnit.DAYS), now),
                metrics.countIssuedBetween(now.minus(365, ChronoUnit.DAYS), now),
                averageSeconds == null ? null : Math.round(averageSeconds / 86_400d),
                named(metrics.countByIssuer(top())));
    }

    /**
     * What the authorities have said, and - the number worth reading first - how much has
     * never been asked about. Not checked is not the same as not revoked.
     */
    private Metrics.Revocation revocation(Instant now) {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (RevocationStatus status : RevocationStatus.values()) {
            byStatus.put(status.name(), certificates.countByRevocationStatus(status));
        }
        return new Metrics.Revocation(
                byStatus,
                metrics.countRevokedBetween(now.minus(30, ChronoUnit.DAYS), now),
                metrics.countRevokedBetween(now.minus(365, ChronoUnit.DAYS), now),
                named(metrics.countByRevocationReason()),
                metrics.oldestRevocationCheck(),
                metrics.newestRevocationCheck());
    }

    /** The directory grouped by what it says about itself, people and servers apart. */
    private Metrics.Attributes attributes() {
        return new Metrics.Attributes(
                named(metrics.countUsersByDutyOrganization(top())),
                named(metrics.countUsersByDutySubOrganization(top())),
                named(metrics.countUsersByEmployeeType(top())),
                named(metrics.countServersByDutyOrganization(top())),
                named(metrics.countServersByDutySubOrganization(top())),
                named(metrics.countServersByEmployeeType(top())));
    }

    /**
     * Enough rows to see the shape and not so many that the page becomes the table it is
     * summarising. A long tail is a finding in itself, and the tables are where it is read.
     */
    private static PageRequest top() {
        return PageRequest.of(0, TOP_VALUES);
    }

    private static List<Metrics.NameCount> named(List<MetricsRepository.NameCount> rows) {
        return rows.stream()
                .map(row -> new Metrics.NameCount(row.getName(), row.getTotal()))
                .toList();
    }

    private Map<String, Long> certificateCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("total", certificates.count());
        for (CertificateStatus status : CertificateStatus.values()) {
            counts.put(status.name(), 0L);
        }
        // The roll-up on the entries is per entry; this is per certificate, which is the
        // question a report about cryptography is asking.
        metricsByStatus().forEach(counts::put);
        return counts;
    }

    private Map<String, Long> metricsByStatus() {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (CertificateStatus status : CertificateStatus.values()) {
            long total = certificates.countByStatus(status);
            if (total > 0) {
                byStatus.put(status.name(), total);
            }
        }
        return byStatus;
    }

    private Map<String, Long> entryCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("users", users.count());
        counts.put("servers", servers.count());
        counts.put("usersWithoutCertificate", users.countByCertificateCount(0));
        counts.put("serversWithoutCertificate", servers.countByCertificateCount(0));
        return counts;
    }

    private List<Metrics.KeyUsage> keyUsage() {
        return metrics.countByKey().stream()
                .map(row -> new Metrics.KeyUsage(
                        row.getAlgorithm(),
                        row.getSize(),
                        label(row.getAlgorithm(), row.getSize()),
                        row.getTotal()))
                .toList();
    }

    private List<Metrics.AlgorithmUsage> hashUsage() {
        return metrics.countByHashAlgorithm().stream()
                .map(row -> new Metrics.AlgorithmUsage(row.getAlgorithm(), row.getTotal()))
                .toList();
    }

    private List<Metrics.AlgorithmUsage> signatureUsage() {
        return metrics.countBySignatureAlgorithm().stream()
                .map(row -> new Metrics.AlgorithmUsage(row.getAlgorithm(), row.getTotal()))
                .toList();
    }

    private Map<String, Long> expiryWindows(Instant now) {
        Map<String, Long> windows = new LinkedHashMap<>();
        windows.put("expired", certificates.countByStatus(CertificateStatus.EXPIRED));
        windows.put("next7", metrics.countExpiringBetween(now, now.plus(7, ChronoUnit.DAYS)));
        windows.put("next30", metrics.countExpiringBetween(now, now.plus(30, ChronoUnit.DAYS)));
        windows.put("next90", metrics.countExpiringBetween(now, now.plus(90, ChronoUnit.DAYS)));
        windows.put("next365", metrics.countExpiringBetween(now, now.plus(365, ChronoUnit.DAYS)));
        return windows;
    }

    /**
     * Issued against expiring, month by month: what the directory took on, and what it has
     * to renew. The months ahead are the useful half - that is the work that is coming.
     */
    private List<Metrics.MonthPoint> monthlySeries(Instant now) {
        YearMonth first = YearMonth.from(now.atZone(ZoneOffset.UTC)).minusMonths(MONTHS_BACK);
        YearMonth last = YearMonth.from(now.atZone(ZoneOffset.UTC)).plusMonths(MONTHS_FORWARD);
        Instant from = first.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = last.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        Map<String, Long> issued = byMonth(metrics.countIssuedByMonth(from, to));
        Map<String, Long> expiring = byMonth(metrics.countExpiringByMonth(from, to));

        List<Metrics.MonthPoint> series = new ArrayList<>();
        for (YearMonth month = first; !month.isAfter(last); month = month.plusMonths(1)) {
            String key = month.toString();
            series.add(new Metrics.MonthPoint(
                    key, issued.getOrDefault(key, 0L), expiring.getOrDefault(key, 0L)));
        }
        return series;
    }

    private Map<String, Long> byMonth(List<MetricsRepository.MonthCount> counts) {
        Map<String, Long> byMonth = new LinkedHashMap<>();
        counts.forEach(count -> byMonth.put(
                YearMonth.of(count.getYear(), count.getMonth()).toString(), count.getTotal()));
        return byMonth;
    }

    private Map<String, Long> notificationCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("raised", notifications.count());
        counts.put("unread", notifications.countByReadAtIsNull());
        counts.put("emailed", notifications.countByEmailedAtIsNotNull());
        // Deliveries attempted, which is a different number: one alert goes to every
        // channel, and a channel that threw is still a delivery that was tried.
        counts.put("delivered", audit.countByAction(AuditAction.ALERT_SENT));
        counts.put("failed", audit.countByAction(AuditAction.ALERT_FAILED));
        return counts;
    }

    private static String label(String algorithm, Integer size) {
        String name = algorithm == null ? "unknown" : algorithm;
        return size == null ? name : name + " " + size;
    }
}
