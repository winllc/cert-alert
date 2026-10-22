package com.winllc.certalert.inventory;

import com.winllc.certalert.api.ApiProperties;
import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectorySpecifications;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.service.ResourceNotFoundException;
import com.winllc.certalert.web.dto.DirectoryServerRow;
import com.winllc.certalert.web.dto.DirectoryUserRow;
import com.winllc.certalert.web.dto.PageResponse;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The inventory as an integrator reads it: certificates with filters, servers, people, and
 * the expiry summary. Open to anyone signed in, as the pages are; with a token, by scope
 * ({@code certificates:read}, {@code people:read}). Documented in the OpenAPI document at
 * {@code /api/v1/openapi.yaml}.
 */
@RestController
@RequestMapping("/api/v1")
public class InventoryApiController {

    private final CertificateInventory inventory;
    private final DirectoryServerRepository servers;
    private final DirectoryUserRepository users;
    private final ApiProperties properties;

    public InventoryApiController(
            CertificateInventory inventory,
            DirectoryServerRepository servers,
            DirectoryUserRepository users,
            ApiProperties properties) {
        this.inventory = inventory;
        this.servers = servers;
        this.users = users;
        this.properties = properties;
    }

    @GetMapping("/certificates")
    public PageResponse<CertificateInventoryRow> certificates(
            CertificateFilter filter,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(required = false) String sort) {
        return PageResponse.of(
                inventory.page(filter, Math.max(page, 0), pageSize(size), CertificateInventory.sort(sort)), row -> row);
    }

    @GetMapping("/certificates/{id:\\d+}")
    public CertificateInventoryRow certificate(@PathVariable Long id) {
        return inventory.find(id);
    }

    @GetMapping("/summary/expiry")
    public CertificateInventory.ExpirySummary expirySummary(CertificateFilter filter) {
        return inventory.summary(filter);
    }

    @GetMapping("/servers")
    @Transactional(readOnly = true)
    public PageResponse<DirectoryServerRow> servers(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) List<CertificateStatus> certificateStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        Specification<DirectoryServer> filter = DirectorySpecifications.<DirectoryServer>certificateStatusIn(certificateStatus)
                .and(text(q, "commonName", "uid", "serverUrl", "dn"));
        return PageResponse.of(
                servers.findAll(filter, PageRequest.of(Math.max(page, 0), pageSize(size), Sort.by("id"))),
                DirectoryServerRow::from);
    }

    @GetMapping("/servers/{id:\\d+}")
    @Transactional(readOnly = true)
    public DirectoryServerRow server(@PathVariable Long id) {
        return servers.findById(id).map(DirectoryServerRow::from).orElseThrow(() -> ResourceNotFoundException.server(id));
    }

    @GetMapping("/people")
    @Transactional(readOnly = true)
    public PageResponse<DirectoryUserRow> people(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) List<CertificateStatus> certificateStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        Specification<DirectoryUser> filter = DirectorySpecifications.<DirectoryUser>certificateStatusIn(certificateStatus)
                .and(text(q, "displayName", "commonName", "uid", "email", "dn"));
        return PageResponse.of(
                users.findAll(filter, PageRequest.of(Math.max(page, 0), pageSize(size), Sort.by("id"))),
                DirectoryUserRow::from);
    }

    @GetMapping("/people/{id:\\d+}")
    @Transactional(readOnly = true)
    public DirectoryUserRow person(@PathVariable Long id) {
        return users.findById(id).map(DirectoryUserRow::from).orElseThrow(() -> ResourceNotFoundException.user(id));
    }

    private int pageSize(int size) {
        return Math.clamp(size, 1, Math.max(1, properties.getMaxPageSize()));
    }

    /** Text in any of these columns, case aside and with the term's own wildcards taken literally. */
    private static <T> Specification<T> text(String q, String... columns) {
        if (q == null || q.isBlank()) {
            return DirectorySpecifications.unfiltered();
        }
        String pattern = "%" + q.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return (root, query, builder) -> {
            List<Predicate> any = new ArrayList<>();
            for (String column : columns) {
                any.add(builder.like(builder.lower(root.get(column)), pattern, '\\'));
            }
            return builder.or(any.toArray(new Predicate[0]));
        };
    }
}
