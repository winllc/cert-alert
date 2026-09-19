package com.winllc.certalert.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A piece of work, with the people and servers that belong to it.
 *
 * <p>The directory has no idea what anything is for. It knows that a person is in an
 * organization and that a server has a point of contact, but not that these six servers and
 * these four people are one system that gets renewed together. That grouping is this
 * application's, and it is what turns "forty certificates expire this month" into "the
 * payroll migration expires this month".
 *
 * <p>Membership is a set on each side rather than an entity of its own: there is nothing to
 * say about a membership beyond its existence. Running the project is the exception, and it
 * is a set of its own - the administrators, who are members as well, kept that way by
 * {@code ProjectService} so that everything asking who is in a project still gets them.
 */
@Entity
@Table(
        name = "project",
        uniqueConstraints = @UniqueConstraint(name = "uk_project_name", columnNames = "name"),
        indexes = @Index(name = "idx_project_name", columnList = "name"))
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "project_seq")
    @SequenceGenerator(name = "project_seq", sequenceName = "project_seq", allocationSize = 1)
    private Long id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 2000)
    private String description;

    @Column(name = "created_by", length = 320)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @ManyToMany(fetch = FetchType.LAZY, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinTable(
            name = "project_user",
            joinColumns = @JoinColumn(name = "project_id", foreignKey = @ForeignKey(name = "fk_project_user_project")),
            inverseJoinColumns =
                    @JoinColumn(name = "user_id", foreignKey = @ForeignKey(name = "fk_project_user_user")))
    private Set<DirectoryUser> members = new LinkedHashSet<>();

    /**
     * Who runs it: they manage the points of contact on every server in the project and
     * hear about those certificates expiring. Always a subset of the members.
     */
    @ManyToMany(fetch = FetchType.LAZY, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinTable(
            name = "project_admin",
            joinColumns = @JoinColumn(name = "project_id", foreignKey = @ForeignKey(name = "fk_project_admin_project")),
            inverseJoinColumns =
                    @JoinColumn(name = "user_id", foreignKey = @ForeignKey(name = "fk_project_admin_user")))
    private Set<DirectoryUser> admins = new LinkedHashSet<>();

    @ManyToMany(fetch = FetchType.LAZY, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinTable(
            name = "project_server",
            joinColumns =
                    @JoinColumn(name = "project_id", foreignKey = @ForeignKey(name = "fk_project_server_project")),
            inverseJoinColumns =
                    @JoinColumn(name = "server_id", foreignKey = @ForeignKey(name = "fk_project_server_server")))
    private Set<DirectoryServer> servers = new LinkedHashSet<>();

    protected Project() {
        // for JPA
    }

    public Project(String name, String description, String createdBy, Instant createdAt) {
        this.name = name;
        this.description = description;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public void rename(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public boolean add(DirectoryUser user) {
        return members.add(user);
    }

    /** Leaving the project gives up running it: a non-member cannot administer it. */
    public boolean remove(DirectoryUser user) {
        admins.remove(user);
        return members.remove(user);
    }

    /** Makes them an administrator, and a member if they were not one already. */
    public boolean promote(DirectoryUser user) {
        members.add(user);
        return admins.add(user);
    }

    public boolean demote(DirectoryUser user) {
        return admins.remove(user);
    }

    public boolean isAdministeredBy(DirectoryUser user) {
        return admins.contains(user);
    }

    public boolean add(DirectoryServer server) {
        return servers.add(server);
    }

    public boolean remove(DirectoryServer server) {
        return servers.remove(server);
    }

    public Set<DirectoryUser> getAdmins() {
        return admins;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Set<DirectoryUser> getMembers() {
        return members;
    }

    public Set<DirectoryServer> getServers() {
        return servers;
    }
}
