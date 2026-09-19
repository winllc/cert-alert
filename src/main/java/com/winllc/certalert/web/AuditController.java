package com.winllc.certalert.web;

import com.winllc.certalert.domain.AuditAction;
import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.domain.OwnerType;
import com.winllc.certalert.repository.AuditEventRepository;
import com.winllc.certalert.repository.AuditSpecifications;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.security.SignedInDirectoryUser;
import com.winllc.certalert.service.AuditService;
import com.winllc.certalert.service.ResourceNotFoundException;
import com.winllc.certalert.web.dto.AuditEventRow;
import com.winllc.certalert.web.dto.AuditSummary;
import com.winllc.certalert.web.dto.PageResponse;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.datatables.mapping.DataTablesInput;
import org.springframework.data.jpa.datatables.mapping.DataTablesOutput;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The audit trail: one entry's history, or the whole of it.
 *
 * <p>An entry's own history is open to anyone signed in. An audit trail nobody may read
 * does not hold anybody to account, and a person looking at a server can see what has been
 * done to it.
 *
 * <p>The whole trail across every entry at once is an administrator's, and that is a
 * different thing from a page of one server's records: read end to end it says who has been
 * here, what they touched and when, which is exactly what an audit trail is for and exactly
 * what should not be handed to everybody signed in. Nothing here writes.
 */
@RestController
@RequestMapping("/api/v1")
public class AuditController {

    private static final int DEFAULT_PAGE_SIZE = 10;

    /** A history is read a page at a time by a person, not exported by the thousand. */
    private static final int MAX_PAGE_SIZE = 100;

    private final AuditService auditService;
    private final AuditEventRepository auditRepository;
    private final DirectoryUserRepository userRepository;
    private final DirectoryServerRepository serverRepository;
    private final Clock clock;

    public AuditController(
            AuditService auditService,
            AuditEventRepository auditRepository,
            DirectoryUserRepository userRepository,
            DirectoryServerRepository serverRepository,
            Clock clock) {
        this.auditService = auditService;
        this.auditRepository = auditRepository;
        this.userRepository = userRepository;
        this.serverRepository = serverRepository;
        this.clock = clock;
    }

    @GetMapping("/users/{id}/audit")
    public PageResponse<AuditEventRow> userHistory(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {

        if (!userRepository.existsById(id)) {
            throw ResourceNotFoundException.user(id);
        }
        return history(OwnerType.USER, id, page, size);
    }

    @GetMapping("/servers/{id}/audit")
    public PageResponse<AuditEventRow> serverHistory(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {

        if (!serverRepository.existsById(id)) {
            throw ResourceNotFoundException.server(id);
        }
        return history(OwnerType.SERVER, id, page, size);
    }

    /**
     * The same records as a search table: DataTables' own paging, ordering and search, over
     * one entry's history or over all of it.
     *
     * <p>Naming a subject is what makes it an entry's history, and that is the table on a
     * details page. Naming none asks for the whole trail, which is the administration page
     * and an administrator's alone.
     *
     * <p>Where a subject is named it goes in as the pre-filter rather than alongside the
     * other filters, because that is the one the count of everything is taken through: a
     * page about one server should say how many records that server has, not how many
     * exist. The administration page wants the opposite - "filtered from 40,000" is the
     * useful thing to print there - so its narrowing is an ordinary filter.
     */
    @PostMapping("/datatables/audit")
    public DataTablesOutput<AuditEventRow> table(
            @Valid @RequestBody DataTablesInput input,
            @RequestParam(required = false) OwnerType subjectType,
            @RequestParam(required = false) Long subjectId,
            @RequestParam(required = false) List<AuditAction> action,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String subject,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Authentication authentication) {

        Specification<AuditEvent> filter = AuditSpecifications.actionIn(action)
                .and(AuditSpecifications.actorLike(actor))
                .and(AuditSpecifications.subjectLike(subject))
                .and(AuditSpecifications.occurredBetween(startOf(from), startOf(to == null ? null : to.plusDays(1))));

        if (subjectId != null) {
            if (subjectType == null) {
                throw new IllegalArgumentException("A subject id needs the subject type alongside it");
            }
            return auditRepository.findAll(
                    input, filter, AuditSpecifications.subject(subjectType, subjectId), AuditEventRow::from);
        }

        requireAdministrator(authentication);
        return auditRepository.findAll(
                input, filter.and(AuditSpecifications.subjectTypeIs(subjectType)), null, AuditEventRow::from);
    }

    /** How much trail there is, and how much of it is recent. Administrators only. */
    @GetMapping("/audit/summary")
    public AuditSummary summary(Authentication authentication) {
        requireAdministrator(authentication);
        Instant now = Instant.now(clock);
        return new AuditSummary(
                auditRepository.count(),
                auditRepository.countByOccurredAtAfter(now.minus(Duration.ofDays(1))),
                auditRepository.countByOccurredAtAfter(now.minus(Duration.ofDays(7))),
                auditRepository.countDistinctActors(),
                auditRepository.earliest(),
                auditRepository.latest());
    }

    private void requireAdministrator(Authentication authentication) {
        if (!SignedInDirectoryUser.isAdmin(authentication)) {
            throw new AccessDeniedException("The whole audit trail is an administrator's to read");
        }
    }

    private Instant startOf(LocalDate day) {
        return day == null ? null : day.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private PageResponse<AuditEventRow> history(OwnerType type, Long id, int page, int size) {
        int bounded = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return PageResponse.of(
                auditService.history(type, id, PageRequest.of(Math.max(page, 0), bounded)), AuditEventRow::from);
    }
}
