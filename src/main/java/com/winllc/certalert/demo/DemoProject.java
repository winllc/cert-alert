package com.winllc.certalert.demo;

import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.domain.Project;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.repository.ProjectRepository;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one project a demo needs, so that running a project is a role somebody can be.
 *
 * <p>Three of the four accounts the sign-in page offers are what they are because of the
 * directory: an administrator is named in configuration, a point of contact is named in a
 * {@code serverPOC}, and a reader is neither. Running a project is not like that - projects
 * are this application's own data, granted by an administrator - so there is nothing a
 * generated directory can say that would create one. Without this, signing in as the
 * project administrator would show exactly what the reader shows, and the sign-in page
 * would be describing a role the demo does not have.
 *
 * <p>A bean of its own rather than a method on the loader, because it has to run inside a
 * transaction: a project cascades to its members and servers, and entities read outside one
 * arrive detached, which JPA refuses to persist. Calling a {@code @Transactional} method on
 * oneself would not go through the proxy that starts it.
 */
@Component
@Conditional(DemoMode.On.class)
public class DemoProject {

    private static final Logger log = LoggerFactory.getLogger(DemoProject.class);

    /**
     * The project the demo creates, named in the seed's description of the account that
     * runs it.
     *
     * <p>Here rather than in the seed because a project is this application's own data, not
     * the directory's: no directory entry can say that somebody runs one.
     */
    public static final String NAME = "Mission Systems";

    /** The role, as the seed spells it, of the account that runs it. */
    private static final String RUNS_IT = "Project administrator";

    /** One server in three, so the project is a slice of the estate rather than all of it. */
    private static final int EVERY = 3;

    private final DirectoryUserRepository users;
    private final DirectoryServerRepository servers;
    private final ProjectRepository projects;
    private final DemoAccounts accounts;

    public DemoProject(
            DirectoryUserRepository users,
            DirectoryServerRepository servers,
            ProjectRepository projects,
            DemoAccounts accounts) {
        this.users = users;
        this.servers = servers;
        this.projects = projects;
        this.accounts = accounts;
    }

    /** Creates it if it is not already there. */
    @Transactional
    public void seed() {
        if (projects.existsByNameIgnoreCase(NAME)) {
            return;
        }
        String runsIt = uidOf(RUNS_IT);
        DirectoryUser administrator = runsIt == null ? null : findBy(runsIt);
        if (administrator == null) {
            log.warn("Demo: the directory holds no account with the role '{}', so there is nobody "
                    + "to run the {} project", RUNS_IT, NAME);
            return;
        }

        Project project = new Project(
                NAME,
                "The servers this project is responsible for, and the people who run it.",
                "demo",
                Instant.now());
        project.add(administrator);
        project.promote(administrator);

        // Servers rather than an empty shell: what running a project grants is the contact
        // lists of the servers in it, so a project with none would grant nothing.
        List<DirectoryServer> all = servers.findAll();
        for (int i = 0; i < all.size(); i += EVERY) {
            project.add(all.get(i));
        }

        // Somebody in it who does not run it, which is the distinction the access policy
        // turns on: membership is a grouping, running it is the authority.
        String contact = uidOf("Point of contact");
        DirectoryUser member = contact == null ? null : findBy(contact);
        if (member != null) {
            project.add(member);
        }

        projects.save(project);
        log.info("Demo: created the {} project over {} server(s), run by {}",
                NAME, (all.size() + EVERY - 1) / EVERY, runsIt);
    }

    /** The uid of the seeded account carrying this role, or null where there is none. */
    private String uidOf(String role) {
        return accounts.all().stream()
                .filter(account -> role.equalsIgnoreCase(account.role()))
                .map(DemoAccounts.Account::uid)
                .findFirst()
                .orElse(null);
    }

    private DirectoryUser findBy(String identifier) {
        List<DirectoryUser> found = users.findByIdentifier(identifier.toLowerCase(Locale.ROOT));
        return found.size() == 1 ? found.getFirst() : null;
    }
}
