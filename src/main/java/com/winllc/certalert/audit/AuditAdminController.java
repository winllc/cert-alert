package com.winllc.certalert.audit;

import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.repository.AuditEventRepository;
import com.winllc.certalert.security.SignedInDirectoryUser;
import com.winllc.certalert.web.dto.PageResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Administration → Audit log ({@code /admin/audit}): the whole trail, newest first, filtered
 * and paged server side; one event in full; export as CSV or NDJSON; integrity verification;
 * the delivery panel; and the SIEM API tokens.
 *
 * <p>Administrators only (the filter chain, and again here). Not a licensed feature, and
 * reachable whatever the license says: the license interceptor lets every path here through
 * ({@code LicensingConfiguration.AUDIT_PATHS}), so an unlicensed or read-only installation's
 * administrators can still open, export and verify it.
 */
@Controller
public class AuditAdminController {

    /** Most events one export carries. */
    public static final int MAX_EXPORT = 100_000;

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final AuditStore store;
    private final AuditEventRepository repository;
    private final AuditLog audit;
    private final AuditVerifier verifier;
    private final AuditDeliveryService delivery;
    private final AuditRetention retention;
    private final AuditTokens tokens;
    private final ObjectMapper json;
    private final Clock clock;

    public AuditAdminController(
            AuditStore store,
            AuditEventRepository repository,
            AuditLog audit,
            AuditVerifier verifier,
            AuditDeliveryService delivery,
            AuditRetention retention,
            AuditTokens tokens,
            ObjectMapper json,
            Clock clock) {
        this.store = store;
        this.repository = repository;
        this.audit = audit;
        this.verifier = verifier;
        this.delivery = delivery;
        this.retention = retention;
        this.tokens = tokens;
        this.json = json;
        this.clock = clock;
    }

    @GetMapping("/admin/audit")
    public String page(Model model, Authentication authentication) {
        requireAdministrator(authentication);
        model.addAttribute("actions", AuditActions.all(store.actions()));
        model.addAttribute("categories", Arrays.stream(AuditRecord.Category.values()).map(AuditRecord.Category::id).toList());
        model.addAttribute("outcomes", Arrays.stream(AuditRecord.Outcome.values()).map(AuditRecord.Outcome::id).toList());
        model.addAttribute("severities", Arrays.stream(AuditRecord.Severity.values()).map(AuditRecord.Severity::id).toList());
        model.addAttribute("retention", retention.describe());
        model.addAttribute("maxExport", MAX_EXPORT);
        return "audit";
    }

    // --- the log -----------------------------------------------------------------------

    /** One row of the log. */
    public record Row(
            long seq,
            Instant time,
            String category,
            String action,
            String outcome,
            String severity,
            String actorType,
            String actorId,
            String actorName,
            String targetType,
            String targetId,
            String targetName,
            String sourceIp) {}

    @GetMapping("/api/v1/admin/audit/events")
    @ResponseBody
    public PageResponse<Row> events(
            Filter filter,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            Authentication authentication) {
        requireAdministrator(authentication);
        return PageResponse.of(store.search(filter.toQuery(), page, size), this::row);
    }

    /** One event in full: every field, the formatted JSON, seq and hash. */
    @GetMapping("/api/v1/admin/audit/events/{seq}")
    @ResponseBody
    public Map<String, Object> event(@PathVariable long seq, Authentication authentication) {
        requireAdministrator(authentication);
        AuditEvent event = store.bySeq(seq).orElseThrow(
                () -> new com.winllc.certalert.service.ResourceNotFoundException("No audit event " + seq));
        JsonNode parsed = json.readTree(event.getLine());
        return Map.of(
                "seq", event.getSeq(),
                "hash", event.getHash(),
                "event", parsed,
                "formatted", json.writerWithDefaultPrettyPrinter().writeValueAsString(parsed),
                "line", event.getLine());
    }

    // --- export --------------------------------------------------------------------------

    /**
     * The current filter, newest first, up to {@value #MAX_EXPORT} events, as CSV or NDJSON.
     * Recorded as {@code audit.exported} - with the filter and the count - before a byte is
     * sent, so there is no export the trail does not know about.
     */
    @GetMapping("/api/v1/admin/audit/export")
    public void export(
            Filter filter,
            @RequestParam(defaultValue = "csv") String format,
            Authentication authentication,
            HttpServletResponse response) throws IOException {
        requireAdministrator(authentication);
        boolean csv = !"ndjson".equalsIgnoreCase(format);
        AuditQuery query = filter.toQuery();
        long matching = repository.count(query.toSpecification());
        int count = (int) Math.min(matching, MAX_EXPORT);
        audit.record(AuditRecord.of(AuditRecord.Category.AUDIT, "audit.exported")
                .detail("format", csv ? "csv" : "ndjson")
                .detail("filter", query.describe())
                .detail("count", count)
                .detail("matching", matching));

        String name = "certalert-audit-" + FILE_STAMP.format(Instant.now(clock)) + (csv ? ".csv" : ".ndjson");
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(csv ? "text/csv;charset=UTF-8" : "application/x-ndjson;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + name + "\"");
        OutputStream out = response.getOutputStream();
        Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        if (csv) {
            writer.write(String.join(",", CSV_COLUMNS));
            writer.write("\r\n");
        }
        store.forEach(query, count, event -> {
            try {
                if (csv) {
                    writer.write(csvRow(event));
                    writer.write("\r\n");
                } else {
                    writer.write(event.getLine());
                    writer.write('\n');
                }
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        writer.flush();
    }

    static final List<String> CSV_COLUMNS = List.of(
            "seq", "time", "category", "action", "outcome", "severity", "actorType", "actorId", "actorName",
            "actorRoles", "sourceIp", "targetType", "targetId", "targetName", "reason", "details", "id", "hash");

    private String csvRow(AuditEvent event) {
        JsonNode node = json.readTree(event.getLine());
        JsonNode actor = node.path("actor");
        JsonNode target = node.path("target");
        List<String> roles = new ArrayList<>();
        actor.path("roles").forEach(role -> roles.add(role.asString()));
        List<String> cells = List.of(
                String.valueOf(event.getSeq()),
                text(node, "time"),
                text(node, "category"),
                text(node, "action"),
                text(node, "outcome"),
                text(node, "severity"),
                text(actor, "type"),
                text(actor, "id"),
                text(actor, "name"),
                String.join(" ", roles),
                text(node.path("source"), "ip"),
                text(target, "type"),
                text(target, "id"),
                text(target, "name"),
                text(node, "reason"),
                node.path("details").toString(),
                text(node, "id"),
                text(node, "hash"));
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                row.append(',');
            }
            row.append(csv(cells.get(i)));
        }
        return row.toString();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asString();
    }

    /** RFC 4180 quoting, and a leading quote on anything a spreadsheet would run as a formula. */
    static String csv(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String safe = "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }

    // --- verification ------------------------------------------------------------------

    @PostMapping("/api/v1/admin/audit/verify")
    @ResponseBody
    public Map<String, Object> verify(Authentication authentication) {
        requireAdministrator(authentication);
        AuditVerifier.Result result = verifier.verify();
        AuditRecord event = AuditRecord.of(AuditRecord.Category.AUDIT, "audit.verified")
                .outcome(result.intact() ? AuditRecord.Outcome.SUCCESS : AuditRecord.Outcome.FAILURE)
                .severity(result.intact() ? AuditRecord.Severity.INFO : AuditRecord.Severity.ALERT)
                .reason(result.intact() ? null : result.problem())
                .detail("intact", result.intact())
                .detail("checked", result.checked())
                .detail("anchorSeq", result.anchorSeq())
                .detail("lastSeq", result.lastSeq())
                .detail("brokenSeq", result.brokenSeq());
        audit.record(event);
        java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("intact", result.intact());
        body.put("checked", result.checked());
        body.put("anchorSeq", result.anchorSeq());
        body.put("anchorPrevHash", result.anchorPrevHash());
        body.put("lastSeq", result.lastSeq());
        body.put("brokenSeq", result.brokenSeq());
        body.put("problem", result.problem());
        body.put("summary", result.summary());
        return body;
    }

    // --- delivery ------------------------------------------------------------------------

    @GetMapping("/api/v1/admin/audit/delivery")
    @ResponseBody
    public Map<String, Object> delivery(Authentication authentication) {
        requireAdministrator(authentication);
        return Map.of(
                "head", store.headSeq(),
                "outputs", delivery.statuses(),
                "retentionDays", retention.retentionDays(),
                "retention", retention.describe());
    }

    // --- tokens ----------------------------------------------------------------------------

    public record TokenRow(
            Long id, String name, String prefix, String createdBy, Instant createdAt, Instant lastUsedAt,
            Instant revokedAt, String revokedBy) {

        static TokenRow of(AuditApiToken token) {
            return new TokenRow(token.getId(), token.getName(), token.getPrefix(), token.getCreatedBy(),
                    token.getCreatedAt(), token.getLastUsedAt(), token.getRevokedAt(), token.getRevokedBy());
        }
    }

    public record TokenRequest(String name) {}

    /** The new token, shown this once. */
    public record CreatedToken(TokenRow token, String secret) {}

    @GetMapping("/api/v1/admin/audit/tokens")
    @ResponseBody
    public List<TokenRow> tokens(Authentication authentication) {
        requireAdministrator(authentication);
        return tokens.list().stream().map(TokenRow::of).toList();
    }

    @PostMapping("/api/v1/admin/audit/tokens")
    @ResponseBody
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedToken createToken(@RequestBody TokenRequest request, Authentication authentication) {
        requireAdministrator(authentication);
        AuditTokens.Created created = tokens.create(request.name(), AuditContext.actorOf(authentication).id());
        return new CreatedToken(TokenRow.of(created.token()), created.secret());
    }

    @DeleteMapping("/api/v1/admin/audit/tokens/{id}")
    @ResponseBody
    public TokenRow revokeToken(@PathVariable Long id, Authentication authentication) {
        requireAdministrator(authentication);
        return TokenRow.of(tokens.revoke(id, AuditContext.actorOf(authentication).id()));
    }

    private static void requireAdministrator(Authentication authentication) {
        if (!SignedInDirectoryUser.isAdmin(authentication)) {
            throw new AccessDeniedException("The audit log is an administrator's to read");
        }
    }

    private Row row(AuditEvent event) {
        String actorName = null;
        String targetName = event.getSubjectName();
        try {
            JsonNode node = json.readTree(event.getLine());
            actorName = text(node.path("actor"), "name");
            targetName = text(node.path("target"), "name");
        } catch (RuntimeException ignored) {
            // the columns are enough to show the row
        }
        return new Row(
                event.getSeq(),
                event.getOccurredAt(),
                event.getCategory(),
                event.getEventAction(),
                event.getOutcome(),
                event.getSeverity(),
                event.getActorType(),
                event.getActorId(),
                actorName == null || actorName.isEmpty() ? null : actorName,
                event.getTargetType(),
                event.getTargetId(),
                targetName == null || targetName.isEmpty() ? null : targetName,
                event.getSourceIp());
    }

    /**
     * The page's filters, bound from the query string.
     *
     * @param from the first day, inclusive (UTC)
     * @param to the last day, inclusive (UTC)
     */
    public record Filter(
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            String category,
            String action,
            String outcome,
            String severity,
            String actor,
            String target,
            String sourceIp,
            String q) {

        AuditQuery toQuery() {
            return new AuditQuery(
                    from == null ? null : from.atStartOfDay(ZoneOffset.UTC).toInstant(),
                    to == null ? null : to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                    category, action, outcome, severity, actor, target, sourceIp, q);
        }
    }
}
