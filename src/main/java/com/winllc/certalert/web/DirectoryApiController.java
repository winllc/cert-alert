package com.winllc.certalert.web;

import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.service.DirectorySyncService;
import com.winllc.certalert.service.ResourceNotFoundException;
import com.winllc.certalert.web.dto.CachedCertificateRow;
import com.winllc.certalert.web.dto.SyncResponse;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Directory sync control, and the cached certificate detail behind a table row. */
@RestController
@RequestMapping("/api/v1")
public class DirectoryApiController {

    private final DirectorySyncService syncService;
    private final DirectoryUserRepository userRepository;
    private final DirectoryServerRepository serverRepository;

    public DirectoryApiController(
            DirectorySyncService syncService,
            DirectoryUserRepository userRepository,
            DirectoryServerRepository serverRepository) {
        this.syncService = syncService;
        this.userRepository = userRepository;
        this.serverRepository = serverRepository;
    }

    /** Runs a directory sync now, rather than waiting for the schedule. */
    @PostMapping("/sync")
    public SyncResponse sync() {
        return SyncResponse.from(syncService.sync());
    }

    @GetMapping("/users/{id}/certificates")
    @Transactional(readOnly = true)
    public List<CachedCertificateRow> userCertificates(@PathVariable Long id) {
        DirectoryUser user =
                userRepository.findWithCertificatesById(id).orElseThrow(() -> ResourceNotFoundException.user(id));
        return user.getCertificates().stream().map(CachedCertificateRow::from).toList();
    }

    @GetMapping("/servers/{id}/certificates")
    @Transactional(readOnly = true)
    public List<CachedCertificateRow> serverCertificates(@PathVariable Long id) {
        DirectoryServer server =
                serverRepository.findWithCertificatesById(id).orElseThrow(() -> ResourceNotFoundException.server(id));
        return server.getCertificates().stream().map(CachedCertificateRow::from).toList();
    }

    /** Points of contact for a server, so a row can link through to the people. */
    @GetMapping("/servers/{id}/contacts")
    @Transactional(readOnly = true)
    public List<String> serverContacts(@PathVariable Long id) {
        DirectoryServer server =
                serverRepository.findWithPocsById(id).orElseThrow(() -> ResourceNotFoundException.server(id));
        return List.copyOf(server.getServerPocs());
    }
}
