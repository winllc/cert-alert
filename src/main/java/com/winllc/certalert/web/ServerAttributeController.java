package com.winllc.certalert.web;

import com.winllc.certalert.domain.ServerAttributeDefinition;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.ServerAttributeValueRepository;
import com.winllc.certalert.security.SignedInDirectoryUser;
import com.winllc.certalert.service.ResourceNotFoundException;
import com.winllc.certalert.service.ServerAttributeService;
import com.winllc.certalert.web.dto.ServerAttributeRequest;
import com.winllc.certalert.web.dto.ServerAttributeRow;
import com.winllc.certalert.web.dto.ServerAttributeValuesRequest;
import com.winllc.certalert.web.dto.ServerAttributes;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
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
 * <p>Defining them is administration - one added here becomes a field on every server at
 * once - so those endpoints sit under {@code /admin} and the filter chain keeps them there.
 * Reading what a server holds is open to anyone signed in, like the rest of a server's
 * details; setting it is an administrator's, which the read says so the page does not offer
 * a control it will refuse.
 */
@RestController
@RequestMapping("/api/v1")
public class ServerAttributeController {

    private final ServerAttributeService attributeService;
    private final ServerAttributeValueRepository valueRepository;
    private final DirectoryServerRepository serverRepository;

    public ServerAttributeController(
            ServerAttributeService attributeService,
            ServerAttributeValueRepository valueRepository,
            DirectoryServerRepository serverRepository) {
        this.attributeService = attributeService;
        this.valueRepository = valueRepository;
        this.serverRepository = serverRepository;
    }

    // -------------------------------------------------------------------------------------
    // The definitions, on the administration page
    // -------------------------------------------------------------------------------------

    @GetMapping("/admin/server-attributes")
    @Transactional(readOnly = true)
    public List<ServerAttributeRow> definitions() {
        Map<Long, Long> holding = valueRepository.countServersByDefinition().stream()
                .collect(Collectors.toMap(
                        ServerAttributeValueRepository.DefinitionUsage::getDefinitionId,
                        ServerAttributeValueRepository.DefinitionUsage::getTotal));
        return attributeService.list().stream()
                .map(definition ->
                        ServerAttributeRow.from(definition, null, holding.getOrDefault(definition.getId(), 0L)))
                .toList();
    }

    @PostMapping("/admin/server-attributes")
    @ResponseStatus(HttpStatus.CREATED)
    public ServerAttributeRow define(@RequestBody ServerAttributeRequest request, Authentication authentication) {
        return ServerAttributeRow.of(attributeService.create(
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
                request.name(),
                request.description(),
                request.type(),
                request.holdsSeveral(),
                request.options(),
                request.displayOrder(),
                nameOf(authentication)));
    }

    /** Retires an attribute, and with it everything held for it. */
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
        Map<Long, List<String>> held = attributeService.valuesFor(id);
        List<ServerAttributeRow> rows = attributeService.list().stream()
                .map(definition -> ServerAttributeRow.from(
                        definition, held.getOrDefault(definition.getId(), List.of()), null))
                .toList();
        return new ServerAttributes(rows, SignedInDirectoryUser.isAdmin(authentication));
    }

    @PutMapping("/servers/{id}/attributes/{definitionId}")
    public ServerAttributeRow setValues(
            @PathVariable Long id,
            @PathVariable Long definitionId,
            @RequestBody ServerAttributeValuesRequest request,
            Authentication authentication) {

        List<String> saved = attributeService.setValues(id, definitionId, request.values(), nameOf(authentication));
        ServerAttributeDefinition definition = attributeService.get(definitionId);
        return ServerAttributeRow.from(definition, saved, null);
    }

    private String nameOf(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        if (authentication.getPrincipal()
                instanceof com.winllc.certalert.security.DirectoryPrincipal principal) {
            return principal.getUsername();
        }
        return authentication.getName();
    }

    /** Definitions by id, where a caller needs to look one up. */
    static Map<Long, ServerAttributeRow> byId(List<ServerAttributeRow> rows) {
        return rows.stream().collect(Collectors.toMap(ServerAttributeRow::id, Function.identity()));
    }
}
