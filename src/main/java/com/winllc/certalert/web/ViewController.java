package com.winllc.certalert.web;

import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.domain.Project;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.service.ProjectService;
import com.winllc.certalert.service.ResourceNotFoundException;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;

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

    public ViewController(
            DirectoryUserRepository userRepository,
            DirectoryServerRepository serverRepository,
            ProjectService projectService) {
        this.userRepository = userRepository;
        this.serverRepository = serverRepository;
        this.projectService = projectService;
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
        // Every value a server's serverPOC could name them by, which is what the join uses.
        model.addAttribute("identifiers", userRepository.findIdentifiersById(id));
        model.addAttribute("projects", projectService.forUser(id));
        return "user-detail";
    }

    @GetMapping("/servers/{id}")
    @Transactional(readOnly = true)
    public String server(@PathVariable Long id, Model model) {
        DirectoryServer server =
                serverRepository.findWithCertificatesById(id).orElseThrow(() -> ResourceNotFoundException.server(id));
        model.addAttribute("server", server);
        model.addAttribute("certificates", byExpiry(server.getCertificates()));
        model.addAttribute("projects", projectService.forServer(id));
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
        return "project-detail";
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

    /**
     * A details page for something that is not there is a page, not a problem detail. The
     * REST advice would answer a browser with JSON, which is the right answer for the API
     * and the wrong one here.
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notFound(ResourceNotFoundException e, Model model) {
        model.addAttribute("message", e.getMessage());
        return "not-found";
    }

    /** Soonest to expire first: on a page about one entry, that is the one being looked for. */
    private List<CachedCertificate> byExpiry(List<CachedCertificate> certificates) {
        return certificates.stream()
                .sorted(Comparator.comparing(
                        CachedCertificate::getNotAfter, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }
}
