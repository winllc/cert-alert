package com.winllc.certalert.service;

import com.winllc.certalert.domain.AuditAction;
import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.domain.Project;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.repository.ProjectRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Projects, and who and what is in them.
 *
 * <p>Joining and leaving are recorded against the entry rather than the project: the
 * question somebody asks later is "why is this server in the payroll project", and they ask
 * it while looking at the server.
 */
@Service
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

    private final ProjectRepository projects;
    private final DirectoryUserRepository users;
    private final DirectoryServerRepository servers;
    private final AuditService auditService;
    private final Clock clock;

    public ProjectService(
            ProjectRepository projects,
            DirectoryUserRepository users,
            DirectoryServerRepository servers,
            AuditService auditService,
            Clock clock) {
        this.projects = projects;
        this.users = users;
        this.servers = servers;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Project> list() {
        return projects.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public Project get(Long id) {
        return projects.findWithMembersAndServersById(id).orElseThrow(() -> notFound(id));
    }

    @Transactional
    public Project create(String name, String description, String createdBy) {
        String trimmed = requireName(name);
        if (projects.existsByNameIgnoreCase(trimmed)) {
            throw new ContactAlreadyExistsException("A project called '%s' already exists".formatted(trimmed));
        }
        Project project = projects.save(new Project(trimmed, description, createdBy, Instant.now(clock)));
        log.info("Created project '{}', by {}", trimmed, createdBy);
        return project;
    }

    @Transactional
    public Project rename(Long id, String name, String description) {
        Project project = projects.findById(id).orElseThrow(() -> notFound(id));
        String trimmed = requireName(name);
        projects.findByNameIgnoreCase(trimmed).ifPresent(other -> {
            if (!other.getId().equals(id)) {
                throw new ContactAlreadyExistsException("A project called '%s' already exists".formatted(trimmed));
            }
        });
        project.rename(trimmed, description);
        return project;
    }

    /**
     * Deletes the project. The entries in it are the directory's and are untouched; only
     * the grouping goes.
     */
    @Transactional
    public void delete(Long id) {
        Project project = projects.findById(id).orElseThrow(() -> notFound(id));
        projects.delete(project);
        log.info("Deleted project '{}'", project.getName());
    }

    @Transactional
    public void addMember(Long projectId, Long userId) {
        Project project = get(projectId);
        DirectoryUser user = users.findById(userId).orElseThrow(() -> ResourceNotFoundException.user(userId));
        if (project.add(user)) {
            record(AuditEvent.SubjectRef.of(user), AuditAction.PROJECT_JOINED, project);
        }
    }

    @Transactional
    public void removeMember(Long projectId, Long userId) {
        Project project = get(projectId);
        DirectoryUser user = users.findById(userId).orElseThrow(() -> ResourceNotFoundException.user(userId));
        if (project.remove(user)) {
            record(AuditEvent.SubjectRef.of(user), AuditAction.PROJECT_LEFT, project);
        }
    }

    @Transactional
    public void addServer(Long projectId, Long serverId) {
        Project project = get(projectId);
        DirectoryServer server =
                servers.findById(serverId).orElseThrow(() -> ResourceNotFoundException.server(serverId));
        if (project.add(server)) {
            record(AuditEvent.SubjectRef.of(server), AuditAction.PROJECT_JOINED, project);
        }
    }

    @Transactional
    public void removeServer(Long projectId, Long serverId) {
        Project project = get(projectId);
        DirectoryServer server =
                servers.findById(serverId).orElseThrow(() -> ResourceNotFoundException.server(serverId));
        if (project.remove(server)) {
            record(AuditEvent.SubjectRef.of(server), AuditAction.PROJECT_LEFT, project);
        }
    }

    @Transactional(readOnly = true)
    public List<Project> forUser(Long userId) {
        return projects.findByMemberId(userId);
    }

    @Transactional(readOnly = true)
    public List<Project> forServer(Long serverId) {
        return projects.findByServerId(serverId);
    }

    private void record(AuditEvent.SubjectRef subject, AuditAction action, Project project) {
        auditService.record(AuditEvent.about(
                        subject,
                        action,
                        "%s the project '%s'".formatted(action == AuditAction.PROJECT_JOINED ? "Added to" : "Removed from",
                                project.getName()),
                        Instant.now(clock))
                .by(AuditActors.current(AuditActors.SYSTEM))
                .to(project.getName()));
    }

    private String requireName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("A project needs a name");
        }
        return trimmed;
    }

    private ResourceNotFoundException notFound(Long id) {
        return new ResourceNotFoundException("No project with id " + id);
    }
}
