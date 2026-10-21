package com.winllc.certalert.export;

import com.winllc.certalert.api.ApiTokenAuthentication;
import com.winllc.certalert.audit.AuditContext;
import com.winllc.certalert.security.SignedInDirectoryUser;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Scheduled reports, managed by a signed-in administrator; auditors may list them. Never with
 * an API token.
 */
@RestController
public class ScheduledReportController {

    public static final String PATH = "/api/v1/admin/reports";

    private final ScheduledReports reports;

    public ScheduledReportController(ScheduledReports reports) {
        this.reports = reports;
    }

    public record ReportRow(
            Long id,
            String name,
            String owner,
            String query,
            String format,
            List<String> recipients,
            String frequency,
            Integer dayOfWeek,
            Integer dayOfMonth,
            int hourUtc,
            boolean enabled,
            Instant nextRunAt,
            Instant lastRunAt,
            Long lastRows,
            String lastError) {

        static ReportRow of(ScheduledReport report) {
            return new ReportRow(report.getId(), report.getName(), report.getOwner(), report.getQuery(),
                    report.getFormat().extension(), report.getRecipients(), report.getFrequency().name(),
                    report.getDayOfWeek(), report.getDayOfMonth(), report.getHourUtc(), report.isEnabled(),
                    report.getNextRunAt(), report.getLastRunAt(), report.getLastRows(), report.getLastError());
        }
    }

    public record EnabledRequest(boolean enabled) {}

    @GetMapping(PATH)
    public List<ReportRow> list(Authentication authentication) {
        requireReader(authentication);
        return reports.list().stream().map(ReportRow::of).toList();
    }

    @PostMapping(PATH)
    @ResponseStatus(HttpStatus.CREATED)
    public ReportRow create(@RequestBody ScheduledReports.Request request, Authentication authentication) {
        requireAdministrator(authentication);
        return ReportRow.of(reports.create(request, AuditContext.actorOf(authentication).id()));
    }

    @PutMapping(PATH + "/{id}/enabled")
    public ReportRow enable(@PathVariable Long id, @RequestBody EnabledRequest request, Authentication authentication) {
        requireAdministrator(authentication);
        return ReportRow.of(reports.enable(id, request.enabled()));
    }

    /** Sends it now, outside its schedule - to check what the recipients will get. */
    @PostMapping(PATH + "/{id}/send")
    public ReportRow sendNow(@PathVariable Long id, Authentication authentication) {
        requireAdministrator(authentication);
        reports.send(id);
        return ReportRow.of(reports.find(id));
    }

    @DeleteMapping(PATH + "/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, Authentication authentication) {
        requireAdministrator(authentication);
        reports.delete(id);
    }

    private static void requireAdministrator(Authentication authentication) {
        if (authentication instanceof ApiTokenAuthentication || !SignedInDirectoryUser.isAdmin(authentication)) {
            throw new AccessDeniedException("Scheduled reports are an administrator's to manage");
        }
    }

    private static void requireReader(Authentication authentication) {
        if (authentication instanceof ApiTokenAuthentication
                || (!SignedInDirectoryUser.isAdmin(authentication) && !SignedInDirectoryUser.isAuditor(authentication))) {
            throw new AccessDeniedException("Scheduled reports are an administrator's or an auditor's to read");
        }
    }
}
