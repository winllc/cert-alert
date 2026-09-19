package com.winllc.certalert.web;

import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.security.DirectoryPrincipal;
import com.winllc.certalert.security.ServerAccessPolicy;
import com.winllc.certalert.service.ResourceNotFoundException;
import com.winllc.certalert.service.ServerContactService;
import com.winllc.certalert.web.dto.AddContactRequest;
import com.winllc.certalert.web.dto.DirectoryServerOption;
import com.winllc.certalert.web.dto.DirectoryUserOption;
import com.winllc.certalert.web.dto.ServerContactRow;
import com.winllc.certalert.web.dto.ServerContacts;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Managing the points of contact for a server.
 *
 * <p>Reading returns both lists: what the directory publishes in {@code serverPOC}, which
 * is a cached copy and not editable here, and what was added here, which is.
 *
 * <p>Who may edit is a question about this server rather than about the application, so it
 * is answered here rather than in the filter chain: a point of contact for the server, or an
 * administrator of a project it belongs to, manages its contacts, as do administrators. See
 * {@link ServerAccessPolicy}. The read says which it is, so the UI shows the controls to
 * the people they will work for rather than offering them and then refusing.
 */
@RestController
@RequestMapping("/api/v1")
public class ServerContactController {

    /** A picker is for picking from, not for paging through. */
    private static final int MAX_SEARCH_RESULTS = 20;

    private final ServerContactService contactService;
    private final DirectoryServerRepository serverRepository;
    private final DirectoryUserRepository userRepository;
    private final ServerAccessPolicy accessPolicy;

    public ServerContactController(
            ServerContactService contactService,
            DirectoryServerRepository serverRepository,
            DirectoryUserRepository userRepository,
            ServerAccessPolicy accessPolicy) {
        this.contactService = contactService;
        this.serverRepository = serverRepository;
        this.userRepository = userRepository;
        this.accessPolicy = accessPolicy;
    }

    @GetMapping("/servers/{id}/contacts")
    @Transactional(readOnly = true)
    public ServerContacts contacts(@PathVariable Long id, Authentication authentication) {
        DirectoryServer server =
                serverRepository.findWithPocsById(id).orElseThrow(() -> ResourceNotFoundException.server(id));
        List<ServerContactRow> managed =
                contactService.list(id).stream().map(ServerContactRow::from).toList();
        return new ServerContacts(
                List.copyOf(server.getServerPocs()), managed, accessPolicy.mayManageContacts(id, authentication));
    }

    @PostMapping("/servers/{id}/contacts")
    @ResponseStatus(HttpStatus.CREATED)
    public ServerContactRow addContact(
            @PathVariable Long id, @Valid @RequestBody AddContactRequest request, Authentication authentication) {

        requireManagement(id, authentication);
        String addedBy = nameOf(authentication);
        return ServerContactRow.from(request.userId() != null
                ? contactService.addUser(id, request.userId(), addedBy)
                : contactService.addEmail(id, request.email(), addedBy));
    }

    @DeleteMapping("/servers/{id}/contacts/{contactId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeContact(@PathVariable Long id, @PathVariable Long contactId, Authentication authentication) {
        requireManagement(id, authentication);
        contactService.remove(id, contactId);
    }

    /** People matching what has been typed into the contact picker. */
    @GetMapping("/users/search")
    @Transactional(readOnly = true)
    public List<DirectoryUserOption> searchUsers(@RequestParam String q) {
        String term = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        if (term.isEmpty()) {
            return List.of();
        }
        return userRepository.searchByIdentifier(term, PageRequest.of(0, MAX_SEARCH_RESULTS)).stream()
                .map(DirectoryUserOption::from)
                .toList();
    }

    /** Servers matching what has been typed, for the picker on a project. */
    @GetMapping("/servers/search")
    @Transactional(readOnly = true)
    public List<DirectoryServerOption> searchServers(@RequestParam String q) {
        String term = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        if (term.isEmpty()) {
            return List.of();
        }
        return serverRepository.searchByName(term, PageRequest.of(0, MAX_SEARCH_RESULTS)).stream()
                .map(DirectoryServerOption::from)
                .toList();
    }

    private void requireManagement(Long serverId, Authentication authentication) {
        if (!accessPolicy.mayManageContacts(serverId, authentication)) {
            throw new AccessDeniedException(
                    "You are not a point of contact for this server, "
                            + "or an administrator of a project it belongs to");
        }
    }

    /** Whoever is signed in, as the directory names them, for the audit trail on the row. */
    private String nameOf(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        if (authentication.getPrincipal() instanceof DirectoryPrincipal principal) {
            return principal.getUsername();
        }
        return authentication.getName();
    }
}
