package com.winllc.certalert.web;

import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.DirectoryEntry;
import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectorySpecifications;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.web.dto.DirectoryServerRow;
import com.winllc.certalert.web.dto.DirectoryUserRow;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.data.jpa.datatables.mapping.DataTablesInput;
import org.springframework.data.jpa.datatables.mapping.DataTablesOutput;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the two search tables.
 *
 * <p>DataTables posts its own state - paging, ordering, global search and per-column
 * search - as the JSON body, which the library turns into the query. The extra filters
 * this application adds ride along as query parameters and become an additional
 * {@link Specification}, so the two concerns never have to know about each other.
 *
 * <p>Rows are converted to DTOs inside the repository call. That keeps lazy associations
 * out of the JSON and means the table never triggers a query per row.
 */
@RestController
@RequestMapping("/api/v1/datatables")
public class DirectoryDataTablesController {

    private final DirectoryUserRepository userRepository;
    private final DirectoryServerRepository serverRepository;
    private final Clock clock;

    public DirectoryDataTablesController(
            DirectoryUserRepository userRepository, DirectoryServerRepository serverRepository, Clock clock) {
        this.userRepository = userRepository;
        this.serverRepository = serverRepository;
        this.clock = clock;
    }

    @PostMapping("/users")
    public DataTablesOutput<DirectoryUserRow> users(
            @Valid @RequestBody DataTablesInput input,
            @RequestParam(required = false) List<CertificateStatus> certificateStatus,
            @RequestParam(required = false) Boolean expired,
            @RequestParam(required = false) Integer expiringWithinDays,
            @RequestParam(required = false) Boolean hasCertificates) {

        Specification<DirectoryUser> filter =
                certificateFilter(certificateStatus, expired, expiringWithinDays, hasCertificates);
        return userRepository.findAll(input, filter, null, DirectoryUserRow::from);
    }

    @PostMapping("/servers")
    public DataTablesOutput<DirectoryServerRow> servers(
            @Valid @RequestBody DataTablesInput input,
            @RequestParam(required = false) List<CertificateStatus> certificateStatus,
            @RequestParam(required = false) Boolean expired,
            @RequestParam(required = false) Integer expiringWithinDays,
            @RequestParam(required = false) Boolean hasCertificates,
            @RequestParam(required = false) String poc,
            @RequestParam(required = false) String pocEmail,
            @RequestParam(required = false) Long pocUserId) {

        Specification<DirectoryServer> filter =
                certificateFilter(certificateStatus, expired, expiringWithinDays, hasCertificates);
        filter = filter.and(pointOfContactFilter(poc != null ? poc : pocEmail, pocUserId));

        return serverRepository.findAll(input, filter, null, DirectoryServerRow::from);
    }

    /**
     * Restricts to the servers a person is the contact for.
     *
     * <p>The table may name that person by a literal {@code serverPOC} value, or by id. An
     * id is resolved here into every value that could name them - their addresses and the
     * forms of their name - because the FSD schema says {@code serverPOC} carries a name
     * while directories in practice often carry an address. Resolving server-side also
     * means the browser never has to know what the join key is.
     */
    private Specification<DirectoryServer> pointOfContactFilter(String poc, Long pocUserId) {
        if (poc != null && !poc.isBlank()) {
            return DirectorySpecifications.pointOfContact(poc);
        }
        if (pocUserId == null) {
            return DirectorySpecifications.unfiltered();
        }
        Set<String> identifiers = userRepository.findIdentifiersById(pocUserId);
        // A user who does not exist, or who the directory gives no name or address, is the
        // contact for nothing. Saying so beats dropping the filter and showing every server.
        return identifiers.isEmpty()
                ? DirectorySpecifications.matchNothing()
                : DirectorySpecifications.pointOfContactAnyOf(identifiers);
    }

    private <T extends DirectoryEntry> Specification<T> certificateFilter(
            List<CertificateStatus> statuses, Boolean expired, Integer expiringWithinDays, Boolean hasCertificates) {

        List<Specification<T>> parts = new ArrayList<>();
        if (statuses != null && !statuses.isEmpty()) {
            parts.add(DirectorySpecifications.certificateStatusIn(statuses));
        }
        if (expired != null) {
            parts.add(DirectorySpecifications.expired(expired));
        }
        if (expiringWithinDays != null && expiringWithinDays > 0) {
            parts.add(DirectorySpecifications.expiringWithinDays(expiringWithinDays, Instant.now(clock)));
        }
        if (hasCertificates != null) {
            parts.add(DirectorySpecifications.hasCertificates(hasCertificates));
        }

        Specification<T> combined = DirectorySpecifications.unfiltered();
        for (Specification<T> part : parts) {
            combined = combined.and(part);
        }
        return combined;
    }
}
