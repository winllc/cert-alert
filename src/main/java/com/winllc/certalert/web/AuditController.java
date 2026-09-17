package com.winllc.certalert.web;

import com.winllc.certalert.domain.AuditAction;
import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.domain.OwnerType;
import com.winllc.certalert.repository.AuditEventRepository;
import com.winllc.certalert.repository.AuditSpecifications;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.service.AuditService;
import com.winllc.certalert.service.ResourceNotFoundException;
import com.winllc.certalert.web.dto.AuditEventRow;
import com.winllc.certalert.web.dto.PageResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.datatables.mapping.DataTablesInput;
import org.springframework.data.jpa.datatables.mapping.DataTablesOutput;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The history of one entry: what happened to it, newest first, a page at a time.
 *
 * <p>Reading is open to anyone signed in. An audit trail nobody may read does not hold
 * anybody to account; the write side is what is worth restricting, and nothing here writes.
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

    public AuditController(
            AuditService auditService,
            AuditEventRepository auditRepository,
            DirectoryUserRepository userRepository,
            DirectoryServerRepository serverRepository) {
        this.auditService = auditService;
        this.auditRepository = auditRepository;
        this.userRepository = userRepository;
        this.serverRepository = serverRepository;
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
     * The same records as a search table, for the details page: DataTables' own paging,
     * ordering and search, narrowed to this entry and optionally to a kind of event.
     */
    @PostMapping("/datatables/audit")
    public DataTablesOutput<AuditEventRow> table(
            @Valid @RequestBody DataTablesInput input,
            @RequestParam OwnerType subjectType,
            @RequestParam Long subjectId,
            @RequestParam(required = false) List<AuditAction> action) {

        // The subject goes in as the pre-filter rather than alongside the others, because
        // that is the one the count of everything is also taken through: a page about one
        // server should say how many records that server has, not how many exist.
        return auditRepository.findAll(
                input,
                AuditSpecifications.actionIn(action),
                AuditSpecifications.subject(subjectType, subjectId),
                AuditEventRow::from);
    }

    private PageResponse<AuditEventRow> history(OwnerType type, Long id, int page, int size) {
        int bounded = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return PageResponse.of(
                auditService.history(type, id, PageRequest.of(Math.max(page, 0), bounded)), AuditEventRow::from);
    }
}
