package com.winllc.certalert.web;

import com.winllc.certalert.domain.ServerAttributeDefinition;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.security.DirectoryPrincipal;
import com.winllc.certalert.security.SignedInDirectoryUser;
import com.winllc.certalert.service.ResourceNotFoundException;
import com.winllc.certalert.service.ServerAttributeService;
import com.winllc.certalert.web.dto.ServerAttributeRequest;
import com.winllc.certalert.web.dto.ServerAttributeRow;
import com.winllc.certalert.web.dto.ServerAttributeValuesRequest;
import com.winllc.certalert.web.dto.ServerAttributes;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The attributes this deployment keeps about its servers: what they are, and what each
 * server holds for them.
 *
 * <p>The values are the directory's: read from the entry when this is asked and written
 * back to it when somebody changes one. Nothing about them is kept here.
 *
 * <p>Saying which attributes may be edited is administration - naming one adds a field to
 * every server at once - so those endpoints sit under {@code /admin} and the filter chain
 * keeps them there. Reading what a server holds is open to anyone signed in, like the rest
 * of a server's details; writing is an administrator's, which the read says so the page does
 * not offer a control it will refuse.
 */
@RestController
@RequestMapping("/api/v1")
public class ServerAttributeController {

    private static final Logger log = LoggerFactory.getLogger(ServerAttributeController.class);

    private final ServerAttributeService attributeService;
    private final DirectoryServerRepository serverRepository;

    public ServerAttributeController(
            ServerAttributeService attributeService, DirectoryServerRepository serverRepository) {
        this.attributeService = attributeService;
        this.serverRepository = serverRepository;
    }

    // -------------------------------------------------------------------------------------
    // The definitions, on the administration page
    // -------------------------------------------------------------------------------------

    @GetMapping("/admin/server-attributes")
    @Transactional(readOnly = true)
    public List<ServerAttributeRow> definitions() {
        return attributeService.list().stream().map(ServerAttributeRow::of).toList();
    }

    @PostMapping("/admin/server-attributes")
    @ResponseStatus(HttpStatus.CREATED)
    public ServerAttributeRow define(@RequestBody ServerAttributeRequest request, Authentication authentication) {
        return ServerAttributeRow.of(attributeService.create(
                request.ldapAttribute(),
                request.name(),
                request.description(),
                request.type(),
                request.holdsSeveral(),
                request.options(),
                request.displayOrder(),
                nameOf(authentication)));
    }

    @PutMapping("/admin/server-attributes/{id}")
    public ServerAttributeRow redefine(
            @PathVariable Long id, @RequestBody ServerAttributeRequest request, Authentication authentication) {

        return ServerAttributeRow.of(attributeService.update(
                id,
                request.ldapAttribute(),
                request.name(),
                request.description(),
                request.type(),
                request.holdsSeveral(),
                request.options(),
                request.displayOrder(),
                nameOf(authentication)));
    }

    /**
     * Stops offering an attribute for editing. The values stay in the directory: they were
     * never this application's to remove.
     */
    @DeleteMapping("/admin/server-attributes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void retire(@PathVariable Long id, Authentication authentication) {
        attributeService.delete(id, nameOf(authentication));
    }

    // -------------------------------------------------------------------------------------
    // What one server holds, on its details page
    // -------------------------------------------------------------------------------------

    @GetMapping("/servers/{id}/attributes")
    @Transactional(readOnly = true)
    public ServerAttributes forServer(@PathVariable Long id, Authentication authentication) {
        if (!serverRepository.existsById(id)) {
            throw ResourceNotFoundException.server(id);
        }
        List<ServerAttributeDefinition> managed = attributeService.list();
        // Not gated on whether this application binds as anybody: an anonymous connection
        // to a directory that permits anonymous writes is unusual but not impossible, and
        // the directory has the last word either way. The page warns instead.
        boolean editable = SignedInDirectoryUser.isAdmin(authentication);

        Map<Long, List<String>> held;
        try {
            held = attributeService.valuesFor(id);
        } catch (RuntimeException e) {
            // The directory holds these; showing none of them as though the entry were
            // empty would be a lie about somebody else's data.
            log.warn("Could not read the managed attributes of server {}", id, e);
            return new ServerAttributes(
                    managed.stream().map(ServerAttributeRow::of).toList(),
                    false,
                    attributeService.canWrite(),
                    "The directory could not be read, so these are not what the entry holds.");
        }

        List<ServerAttributeRow> rows = managed.stream()
                .map(definition ->
                        ServerAttributeRow.from(definition, held.getOrDefault(definition.getId(), List.of())))
                .toList();
        return new ServerAttributes(rows, editable, attributeService.canWrite(), null);
    }

    @PutMapping("/servers/{id}/attributes/{definitionId}")
    public ServerAttributeRow setValues(
            @PathVariable Long id,
            @PathVariable Long definitionId,
            @RequestBody ServerAttributeValuesRequest request,
            Authentication authentication) {

        List<String> saved = attributeService.setValues(id, definitionId, request.values(), nameOf(authentication));
        return ServerAttributeRow.from(attributeService.get(definitionId), saved);
    }

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
