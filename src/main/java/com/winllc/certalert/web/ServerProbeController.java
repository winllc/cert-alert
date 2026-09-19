package com.winllc.certalert.web;

import com.winllc.certalert.security.DirectoryPrincipal;
import com.winllc.certalert.security.ServerAccessPolicy;
import com.winllc.certalert.service.ServerProbeService;
import com.winllc.certalert.web.dto.ProbeView;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Asking a server what it is serving.
 *
 * <p>A POST, although it reads rather than writes: it makes this application open a
 * connection to somewhere else, which is not something a link, a crawler or a prefetch
 * should be able to cause. The CSRF token comes along with that.
 *
 * <p>Who may ask is the same question as who may manage the server's contacts - the people
 * with something to do with this server - so it is answered by the same policy. An
 * administrator, a point of contact, or an administrator of a project the server belongs
 * to. See {@link ServerAccessPolicy}.
 *
 * <p>The port is the only thing the caller chooses. The host comes from the directory
 * entry, so this cannot be turned into a way of reaching an arbitrary address from inside
 * the network the application runs on.
 */
@RestController
@RequestMapping("/api/v1")
public class ServerProbeController {

    private final ServerProbeService probeService;
    private final ServerAccessPolicy accessPolicy;

    public ServerProbeController(ServerProbeService probeService, ServerAccessPolicy accessPolicy) {
        this.probeService = probeService;
        this.accessPolicy = accessPolicy;
    }

    @PostMapping("/servers/{id}/probe")
    public ProbeView probe(
            @PathVariable Long id,
            @RequestParam(required = false) Integer port,
            Authentication authentication) {

        if (!accessPolicy.mayManageContacts(id, authentication)) {
            throw new AccessDeniedException("You are not a point of contact for this server");
        }
        return ProbeView.from(probeService.probe(id, port, nameOf(authentication)));
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
