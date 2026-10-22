package com.winllc.certalert.inventory;

import com.winllc.certalert.api.ApiTokenAuthentication;
import com.winllc.certalert.audit.AuditContext;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.security.ServerAccessPolicy;
import com.winllc.certalert.service.AlreadyExistsException;
import com.winllc.certalert.service.ResourceNotFoundException;
import com.winllc.certalert.service.ServerContactService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bulk owner assignment: one point of contact added to many servers at once - the servers
 * behind the certificates picked on the inventory page.
 *
 * <p>Server by server, under the same rule as adding one (see {@link ServerAccessPolicy}): a
 * server the person may not manage is reported as refused and left alone, rather than failing
 * the lot. Each addition is audited as one would be. For people only; not with an API token.
 */
@RestController
public class BulkContactController {

    public static final String PATH = "/api/v1/servers/contacts/bulk";

    static final int MAX_SERVERS = 500;

    private final ServerContactService contacts;
    private final ServerAccessPolicy policy;
    private final DirectoryServerRepository servers;

    public BulkContactController(ServerContactService contacts, ServerAccessPolicy policy, DirectoryServerRepository servers) {
        this.contacts = contacts;
        this.policy = policy;
        this.servers = servers;
    }

    /** An address, or a person in the directory, for every server listed. */
    public record BulkContactRequest(List<Long> serverIds, Long userId, String email) {}

    public record BulkContactResult(int added, List<Long> alreadyContact, List<Long> refused, List<Long> notFound) {}

    @PostMapping(PATH)
    public BulkContactResult add(@RequestBody BulkContactRequest request, Authentication authentication) {
        if (authentication instanceof ApiTokenAuthentication) {
            throw new AccessDeniedException("Bulk changes are made by people");
        }
        boolean byUser = request.userId() != null;
        boolean byEmail = request.email() != null && !request.email().isBlank();
        if (byUser == byEmail) {
            throw new IllegalArgumentException("Name either a person in the directory or an address, not both");
        }
        if (byEmail && request.email().length() > 320) {
            throw new IllegalArgumentException("That address is too long");
        }
        Set<Long> ids = new LinkedHashSet<>(request.serverIds() == null ? List.of() : request.serverIds());
        ids.remove(null);
        if (ids.isEmpty() || ids.size() > MAX_SERVERS) {
            throw new IllegalArgumentException("Pick between 1 and " + MAX_SERVERS + " servers");
        }
        String by = AuditContext.actorOf(authentication).id();
        int added = 0;
        List<Long> already = new ArrayList<>();
        List<Long> refused = new ArrayList<>();
        List<Long> notFound = new ArrayList<>();
        for (Long id : ids) {
            if (!servers.existsById(id)) {
                notFound.add(id);
                continue;
            }
            if (!policy.mayManageContacts(id, authentication)) {
                refused.add(id);
                continue;
            }
            try {
                if (byUser) {
                    contacts.addUser(id, request.userId(), by);
                } else {
                    contacts.addEmail(id, request.email().trim(), by);
                }
                added++;
            } catch (AlreadyExistsException e) {
                already.add(id);
            } catch (ResourceNotFoundException e) {
                notFound.add(id);
            }
        }
        return new BulkContactResult(added, already, refused, notFound);
    }
}
