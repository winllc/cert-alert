package com.winllc.certalert.export;

import com.winllc.certalert.audit.AuditLog;
import com.winllc.certalert.audit.AuditRecord;
import com.winllc.certalert.cluster.JobLocks;
import com.winllc.certalert.config.NotificationProperties;
import com.winllc.certalert.domain.EmailAddresses;
import com.winllc.certalert.domain.OutboundEmail;
import com.winllc.certalert.inventory.CertificateFilters;
import com.winllc.certalert.inventory.CertificateInventory;
import com.winllc.certalert.service.MailOutbox;
import com.winllc.certalert.service.ResourceNotFoundException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Scheduled reports: an administrator's standing request to email the certificate inventory,
 * narrowed by a saved filter, as CSV or Excel.
 *
 * <p>Looked at every few minutes, on every replica, under the database lock every scheduled job
 * takes (ShedLock, see {@link JobLocks}), so one replica sends each report once. A report that
 * is due is built from a database cursor - at most {@code cert-alert.exports.report-max-rows}
 * rows, the email saying so when there were more - and queued through the mail outbox, which
 * sends it after the commit and retries it while the mail server is down. Each send is audited
 * as {@code report.sent}, with its recipients and row count; creating and deleting one too.
 *
 * <p>Administrators only: a report goes to addresses somebody types, so it is a way to send
 * the inventory out of the building, and {@code cert-alert.exports.report-recipient-domains}
 * can hold it to the organisation's own.
 */
@Service
@EnableScheduling
public class ScheduledReports {

    private static final Logger log = LoggerFactory.getLogger(ScheduledReports.class);

    static final String LOCK = "scheduled-reports";

    /** The largest attachment sent; a mail server's own limit is usually 10-25 MB. */
    static final int MAX_ATTACHMENT_BYTES = 15 * 1024 * 1024;

    private final ScheduledReportRepository reports;
    private final CertificateExport export;
    private final CertificateInventory inventory;
    private final MailOutbox outbox;
    private final NotificationProperties notifications;
    private final ExportProperties properties;
    private final JobLocks locks;
    private final AuditLog audit;
    private final Clock clock;
    private final TransactionTemplate independent;

    public ScheduledReports(
            ScheduledReportRepository reports,
            CertificateExport export,
            CertificateInventory inventory,
            MailOutbox outbox,
            NotificationProperties notifications,
            ExportProperties properties,
            JobLocks locks,
            AuditLog audit,
            Clock clock,
            PlatformTransactionManager transactions) {
        this.reports = reports;
        this.export = export;
        this.inventory = inventory;
        this.outbox = outbox;
        this.notifications = notifications;
        this.properties = properties;
        this.locks = locks;
        this.audit = audit;
        this.clock = clock;
        this.independent = new TransactionTemplate(transactions);
        this.independent.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    public record Request(
            String name,
            String query,
            String format,
            List<String> recipients,
            ScheduledReport.Frequency frequency,
            Integer dayOfWeek,
            Integer dayOfMonth,
            Integer hourUtc) {}

    @Transactional
    public ScheduledReport create(Request request, String owner) {
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty() || name.length() > 120) {
            throw new IllegalArgumentException("Give the report a name of up to 120 characters");
        }
        String query = request.query() == null ? "" : request.query().trim();
        CertificateFilters.parse(query);
        CertificateExport.Format format = CertificateExport.Format.of(request.format());
        List<String> recipients = recipients(request.recipients());
        ScheduledReport.Frequency frequency = request.frequency() == null ? ScheduledReport.Frequency.WEEKLY : request.frequency();
        int hour = request.hourUtc() == null ? 6 : request.hourUtc();
        if (hour < 0 || hour > 23) {
            throw new IllegalArgumentException("The hour is 0 to 23, UTC");
        }
        Integer dayOfWeek = null;
        Integer dayOfMonth = null;
        if (frequency == ScheduledReport.Frequency.WEEKLY) {
            dayOfWeek = request.dayOfWeek() == null ? 1 : request.dayOfWeek();
            if (dayOfWeek < 1 || dayOfWeek > 7) {
                throw new IllegalArgumentException("The day of the week is 1 (Monday) to 7 (Sunday)");
            }
        } else if (frequency == ScheduledReport.Frequency.MONTHLY) {
            dayOfMonth = request.dayOfMonth() == null ? 1 : request.dayOfMonth();
            if (dayOfMonth < 1 || dayOfMonth > 28) {
                throw new IllegalArgumentException("The day of the month is 1 to 28");
            }
        }
        if (reports.countByOwner(owner) >= properties.getMaxReportsPerUser()) {
            throw new IllegalArgumentException("You have " + properties.getMaxReportsPerUser() + " scheduled reports; delete one first");
        }
        ScheduledReport report = reports.save(new ScheduledReport(
                owner, name, query, format, recipients, frequency, dayOfWeek, dayOfMonth, hour, Instant.now(clock)));
        audit.record(AuditRecord.of(AuditRecord.Category.CONFIGURATION, "report.created")
                .severity(AuditRecord.Severity.NOTICE)
                .target("scheduled-report", String.valueOf(report.getId()), name)
                .detail("recipients", String.join(",", recipients))
                .detail("format", format.extension())
                .detail("frequency", frequency.name())
                .detail("filter", query));
        return report;
    }

    @Transactional
    public void delete(Long id) {
        ScheduledReport report = find(id);
        reports.delete(report);
        audit.record(AuditRecord.of(AuditRecord.Category.CONFIGURATION, "report.deleted")
                .severity(AuditRecord.Severity.NOTICE)
                .target("scheduled-report", String.valueOf(id), report.getName()));
    }

    @Transactional
    public ScheduledReport enable(Long id, boolean enabled) {
        ScheduledReport report = find(id);
        report.enabled(enabled, Instant.now(clock));
        return report;
    }

    @Transactional(readOnly = true)
    public List<ScheduledReport> list() {
        return reports.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public ScheduledReport find(Long id) {
        return reports.findById(id).orElseThrow(() -> new ResourceNotFoundException("No scheduled report " + id));
    }

    @Scheduled(fixedDelayString = "${cert-alert.exports.report-poll-interval:PT5M}", initialDelayString = "PT1M")
    public void poll() {
        try {
            locks.runScheduled(LOCK, this::runDue);
        } catch (RuntimeException e) {
            log.error("Sending the scheduled reports failed; they are tried again on the next pass", e);
        }
    }

    /** Sends every report that is due, and says how many went. */
    public int runDue() {
        int sent = 0;
        for (ScheduledReport due : reports.findByEnabledTrueAndNextRunAtLessThanEqualOrderByNextRunAtAsc(Instant.now(clock))) {
            if (send(due.getId())) {
                sent++;
            }
        }
        return sent;
    }

    /** Builds one report's file and queues its email; the schedule moves on whether or not it worked. */
    public boolean send(Long id) {
        ScheduledReport report = find(id);
        Instant now = Instant.now(clock);
        CertificateFilters.Parsed parsed;
        long rows = 0;
        try {
            parsed = CertificateFilters.parse(report.getQuery());
            long matching = inventory.count(parsed.filter());
            long limit = Math.min(matching, properties.getReportMaxRows());
            ByteArrayOutputStream file = new ByteArrayOutputStream();
            rows = export.write(parsed.filter(), CertificateInventory.sort(parsed.sort()), report.getFormat(), limit,
                    "scheduled report \"" + report.getName() + "\"", new BoundedStream(file, MAX_ATTACHMENT_BYTES));
            String marking = export.marking();
            String subject = (marking == null ? "" : "[" + marking + "] ") + notifications.getEmail().getSubjectPrefix()
                    + report.getName() + " - " + rows + " certificate" + (rows == 1 ? "" : "s");
            StringBuilder body = new StringBuilder();
            if (marking != null) {
                body.append(marking).append("\n\n");
            }
            body.append("The CertAlert report \"").append(report.getName()).append("\" is attached: ")
                    .append(rows).append(" certificate").append(rows == 1 ? "" : "s").append(", as of ").append(now).append(".\n");
            if (matching > rows) {
                body.append("\n").append(matching).append(" matched; the file holds the first ").append(rows)
                        .append(" (cert-alert.exports.report-max-rows). Narrow the filter or download the rest.\n");
            }
            body.append("\nFilters: ").append(report.getQuery().isBlank() ? "none" : report.getQuery()).append('\n');
            body.append("\nThis report is scheduled ").append(report.getFrequency().name().toLowerCase(Locale.ROOT))
                    .append(" by an administrator of CertAlert; ask them to change or stop it.\n");
            if (marking != null) {
                body.append('\n').append(marking).append('\n');
            }
            long written = rows;
            OutboundEmail message = new OutboundEmail(OutboundEmail.Kind.REPORT, notifications.getEmail().getFrom(),
                    report.getRecipients(), subject, body.toString(), null, "report:" + report.getId(), null, now)
                    .attach(export.fileName(report.getFormat()), report.getFormat().contentType(), file.toByteArray());
            independent.executeWithoutResult(status -> {
                outbox.queue(message);
                reports.findById(id).ifPresent(row -> row.ran(now, written, null));
                audit.record(AuditRecord.of(AuditRecord.Category.DATA, "report.sent")
                        .actor(AuditRecord.Actor.system("scheduler"))
                        .target("scheduled-report", String.valueOf(id), report.getName())
                        .detail("recipients", String.join(",", report.getRecipients()))
                        .detail("format", report.getFormat().extension())
                        .detail("count", written)
                        .detail("matching", matching));
            });
            return true;
        } catch (IOException | RuntimeException e) {
            String error = e instanceof BoundedStream.TooLargeException
                    ? "The file came to more than " + (MAX_ATTACHMENT_BYTES / (1024 * 1024)) + " MB; narrow the filter"
                    : (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            log.warn("The scheduled report {} could not be sent: {}", id, error.replaceAll("[\\r\\n]", " "));
            long written = rows;
            independent.executeWithoutResult(status -> reports.findById(id).ifPresent(row -> row.ran(now, written, error)));
            audit.recordQuietly(AuditRecord.of(AuditRecord.Category.DATA, "report.sent")
                    .actor(AuditRecord.Actor.system("scheduler"))
                    .outcome(AuditRecord.Outcome.FAILURE)
                    .target("scheduled-report", String.valueOf(id), report.getName())
                    .reason(error));
            return false;
        }
    }

    private List<String> recipients(List<String> given) {
        Set<String> addresses = new LinkedHashSet<>();
        for (String value : given == null ? List.<String>of() : given) {
            for (String part : value.split("[,;\\s]+")) {
                if (part.isBlank()) {
                    continue;
                }
                String address = part.trim().toLowerCase(Locale.ROOT);
                if (!EmailAddresses.isAddress(address)) {
                    throw new IllegalArgumentException("'" + part + "' is not an email address");
                }
                if (!allowedDomain(address)) {
                    throw new IllegalArgumentException("Reports may only be sent to "
                            + String.join(", ", properties.getReportRecipientDomains()) + " (cert-alert.exports.report-recipient-domains)");
                }
                addresses.add(address);
            }
        }
        if (addresses.isEmpty() || addresses.size() > 20) {
            throw new IllegalArgumentException("Send a report to between 1 and 20 addresses");
        }
        return new ArrayList<>(addresses);
    }

    private boolean allowedDomain(String address) {
        List<String> domains = properties.getReportRecipientDomains();
        if (domains == null || domains.isEmpty()) {
            return true;
        }
        String domain = address.substring(address.lastIndexOf('@') + 1);
        for (String allowed : domains) {
            String value = allowed.trim().toLowerCase(Locale.ROOT);
            if (value.startsWith("*.") ? domain.endsWith(value.substring(1)) : domain.equals(value)) {
                return true;
            }
        }
        return false;
    }

    /** An output that refuses to grow past a limit, so a report cannot fill the heap. */
    static final class BoundedStream extends java.io.OutputStream {

        static final class TooLargeException extends IOException {
            TooLargeException() {
                super("too large");
            }
        }

        private final ByteArrayOutputStream out;
        private final int limit;

        BoundedStream(ByteArrayOutputStream out, int limit) {
            this.out = out;
            this.limit = limit;
        }

        @Override
        public void write(int b) throws IOException {
            if (out.size() + 1 > limit) {
                throw new TooLargeException();
            }
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (out.size() + len > limit) {
                throw new TooLargeException();
            }
            out.write(b, off, len);
        }
    }
}
