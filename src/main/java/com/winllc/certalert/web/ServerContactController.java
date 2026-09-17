package com.winllc.certalert.web;

import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.security.DirectoryPrincipal;
import com.winllc.certalert.security.DirectoryPrincipalResolver;
import com.winllc.certalert.security.SecurityProperties;
import com.winllc.certalert.service.ResourceNotFoundException;
import com.winllc.certalert.service.ServerContactService;
import com.winllc.certalert.web.dto.AddContactRequest;
import com.winllc.certalert.web.dto.DirectoryUserOption;
import com.winllc.certalert.web.dto.ServerContactRow;
import com.winllc.certalert.web.dto.ServerContacts;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
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
 * is a cached copy and not editable here, and what was added here, which is. Who may edit
 * is {@code cert-alert.security.contact-editors}, enforced in the filter chain; the read
 * says which it is so the UI does not offer a button that will be refused.
 */
@RestController
@RequestMapping("/api/v1")
public class ServerContactController {

    /** A picker is for picking from, not for paging through. */
    private static final int MAX_SEARCH_RESULTS = 20;

    private final ServerContactService contactService;
    private final DirectoryServerRepository serverRepository;
    private final DirectoryUserRepository userRepository;
    private final SecurityProperties securityProperties;

    public ServerContactController(
            ServerContactService contactService,
            DirectoryServerRepository serverRepository,
            DirectoryUserRepository userRepository,
            SecurityProperties securityProperties) {
        this.contactService = contactService;
        this.serverRepository = serverRepository;
        this.userRepository = userRepository;
        this.securityProperties = securityProperties;
    }

    @GetMapping("/servers/{id}/contacts")
    @Transactional(readOnly = true)
    public ServerContacts contacts(@PathVariable Long id, Authentication authentication) {
        DirectoryServer server =
                serverRepository.findWithPocsById(id).orElseThrow(() -> ResourceNotFoundException.server(id));
        List<ServerContactRow> managed =
                contactService.list(id).stream().map(ServerContactRow::from).toList();
        return new ServerContacts(List.copyOf(server.getServerPocs()), managed, mayEdit(authentication));
    }

    @PostMapping("/servers/{id}/contacts")
    @ResponseStatus(HttpStatus.CREATED)
    public ServerContactRow addContact(
            @PathVariable Long id, @Valid @RequestBody AddContactRequest request, Authentication authentication) {

        String addedBy = nameOf(authentication);
        return ServerContactRow.from(request.userId() != null
                ? contactService.addUser(id, request.userId(), addedBy)
                : contactService.addEmail(id, request.email(), addedBy));
    }

    @DeleteMapping("/servers/{id}/contacts/{contactId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeContact(@PathVariable Long id, @PathVariable Long contactId) {
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

    private boolean mayEdit(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        if (securityProperties.getContactEditors() == SecurityProperties.ContactEditors.AUTHENTICATED) {
            return true;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> DirectoryPrincipalResolver.ROLE_ADMIN.equals(authority.getAuthority()));
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
