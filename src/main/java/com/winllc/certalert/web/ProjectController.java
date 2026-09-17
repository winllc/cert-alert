package com.winllc.certalert.web;

import com.winllc.certalert.domain.Project;
import com.winllc.certalert.repository.ProjectRepository;
import com.winllc.certalert.security.DirectoryPrincipal;
import com.winllc.certalert.service.ProjectService;
import com.winllc.certalert.web.dto.ProjectRequest;
import com.winllc.certalert.web.dto.ProjectRow;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
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

/** Creating projects and saying who and what is in them. */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final ProjectRepository projectRepository;

    public ProjectController(ProjectService projectService, ProjectRepository projectRepository) {
        this.projectService = projectService;
        this.projectRepository = projectRepository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<ProjectRow> list() {
        Map<Long, ProjectRepository.ProjectSize> sizes = projectRepository.countMembership().stream()
                .collect(Collectors.toMap(ProjectRepository.ProjectSize::getProjectId, size -> size));
        return projectService.list().stream()
                .map(project -> {
                    ProjectRepository.ProjectSize size = sizes.get(project.getId());
                    return ProjectRow.from(
                            project, size == null ? 0 : size.getMembers(), size == null ? 0 : size.getServers());
                })
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectRow create(@Valid @RequestBody ProjectRequest request, Authentication authentication) {
        Project project = projectService.create(request.name(), request.description(), nameOf(authentication));
        return ProjectRow.of(project);
    }

    @PutMapping("/{id}")
    public ProjectRow rename(@PathVariable Long id, @Valid @RequestBody ProjectRequest request) {
        return ProjectRow.of(projectService.rename(id, request.name(), request.description()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        projectService.delete(id);
    }

    @PostMapping("/{id}/users/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addMember(@PathVariable Long id, @PathVariable Long userId) {
        projectService.addMember(id, userId);
    }

    @DeleteMapping("/{id}/users/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable Long id, @PathVariable Long userId) {
        projectService.removeMember(id, userId);
    }

    @PostMapping("/{id}/servers/{serverId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addServer(@PathVariable Long id, @PathVariable Long serverId) {
        projectService.addServer(id, serverId);
    }

    @DeleteMapping("/{id}/servers/{serverId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeServer(@PathVariable Long id, @PathVariable Long serverId) {
        projectService.removeServer(id, serverId);
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
