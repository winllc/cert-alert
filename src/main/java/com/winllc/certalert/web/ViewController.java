package com.winllc.certalert.web;

import com.winllc.certalert.config.CredentialProperties;
import com.winllc.certalert.domain.AuditAction;
import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.domain.Project;
import com.winllc.certalert.config.ProbeProperties;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.security.ServerAccessPolicy;
import com.winllc.certalert.service.CertificateIssuance;
import com.winllc.certalert.service.EndpointAddress;
import com.winllc.certalert.service.ProjectService;
import com.winllc.certalert.service.ServerProbeService;
import com.winllc.certalert.service.ResourceNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * The pages.
 *
 * <p>The two search pages fetch everything through the DataTables endpoints, because what
 * they show depends on paging and filters the browser owns. The details pages are the other
 * way round: one entry, known at request time, so its attributes and certificates are
 * rendered with the page. Only the two things that change while it is open - the points of
 * contact and the audit table - are fetched.
 */
@Controller
public class ViewController {

    private final DirectoryUserRepository userRepository;
    private final DirectoryServerRepository serverRepository;
    private final ProjectService projectService;
    private final CredentialProperties credentials;
    private final ServerProbeService probeService;
    private final ServerAccessPolicy accessPolicy;
    private final ProbeProperties probeProperties;
    private final Clock clock;

    public ViewController(
            DirectoryUserRepository userRepository,
            DirectoryServerRepository serverRepository,
            ProjectService projectService,
            CredentialProperties credentials,
            ServerProbeService probeService,
            ServerAccessPolicy accessPolicy,
            ProbeProperties probeProperties,
            Clock clock) {
        this.userRepository = userRepository;
        this.serverRepository = serverRepository;
        this.projectService = projectService;
        this.credentials = credentials;
        this.probeService = probeService;
        this.accessPolicy = accessPolicy;
        this.probeProperties = probeProperties;
        this.clock = clock;
    }

    @GetMapping("/")
    public String index() {
        return "redirect:/users";
    }

    @GetMapping("/users")
    public String users() {
        return "users";
    }

    @GetMapping("/servers")
    public String servers() {
        return "servers";
    }

    @GetMapping("/users/{id}")
    @Transactional(readOnly = true)
    public String user(@PathVariable Long id, Model model) {
        DirectoryUser user =
                userRepository.findWithCertificatesById(id).orElseThrow(() -> ResourceNotFoundException.user(id));
        model.addAttribute("user", user);
        model.addAttribute("certificates", byExpiry(user.getCertificates()));
        // The pair rather than the newest one: a person holds a signing certificate and an
        // encryption certificate, and half a renewal is only visible with both in view.
        model.addAttribute(
                "credentials",
                CertificateIssuance.current(
                        user.getCertificates(), credentials.getPairWindow(), Instant.now(clock)));
        // Every value a server's serverPOC could name them by, which is what the join uses.
        model.addAttribute("identifiers", userRepository.findIdentifiersById(id));
        model.addAttribute("projects", projectService.forUser(id));
        model.addAttribute("administers", projectService.administeredBy(id));
        model.addAttribute("actions", AuditAction.values());
        return "user-detail";
    }

    @GetMapping("/servers/{id}")
    @Transactional(readOnly = true)
    public String server(@PathVariable Long id, Model model, Authentication authentication) {
        DirectoryServer server =
                serverRepository.findWithCertificatesById(id).orElseThrow(() -> ResourceNotFoundException.server(id));
        model.addAttribute("server", server);
        model.addAttribute("certificates", byExpiry(server.getCertificates()));
        model.addAttribute("projects", projectService.forServer(id));
        model.addAttribute("actions", AuditAction.values());
        // The probe opens a connection to somewhere else, so the card is offered only to
        // the people who may run it - and only where there is somewhere to run it against.
        model.addAttribute(
                "mayProbe",
                probeProperties.isEnabled()
                        && EndpointAddress.of(server, null, probeProperties.getDefaultPort()).isPresent()
                        && accessPolicy.mayManageContacts(id, authentication));
        model.addAttribute("probePort", probeService.defaultPortFor(server));
        return "server-detail";
    }

    @GetMapping("/projects")
    public String projects() {
        return "projects";
    }

    @GetMapping("/projects/{id}")
    @Transactional(readOnly = true)
    public String project(@PathVariable Long id, Model model) {
        Project project = projectService.get(id);
        model.addAttribute("project", project);
        model.addAttribute("members", project.getMembers().stream()
                .sorted(java.util.Comparator.comparing(
                        DirectoryUser::getDisplayName, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList());
        model.addAttribute("servers", project.getServers().stream()
                .sorted(java.util.Comparator.comparing(
                        DirectoryServer::getCommonName, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList());
        // Ids rather than entities: the page asks "is this member one of them" per badge.
        model.addAttribute("adminIds", project.getAdmins().stream().map(DirectoryUser::getId).toList());
        return "project-detail";
    }

    @GetMapping("/metrics")
    public String metrics() {
        return "metrics";
    }

    /**
     * Administration: the audit trail across every entry, which the filter chain keeps to
     * administrators. The kinds of event come from the enum rather than a list in the
     * template, so a new one appears in the filter the day it is added.
     */
    @GetMapping("/admin")
    public String admin(Model model) {
        model.addAttribute("actions", AuditAction.values());
        return "admin";
    }

    @GetMapping("/notifications")
    public String notifications() {
        return "notifications";
    }

    /** The password fallback, for a browser that presented no client certificate. */
    @GetMapping("/login")
    public String login() {
        return "login";
    }

    /** Soonest to expire first: on a page about one entry, that is the one being looked for. */
    private List<CachedCertificate> byExpiry(List<CachedCertificate> certificates) {
        return certificates.stream()
                .sorted(Comparator.comparing(
                        CachedCertificate::getNotAfter, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }
}
