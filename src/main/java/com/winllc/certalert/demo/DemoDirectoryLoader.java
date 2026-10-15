package com.winllc.certalert.demo;

import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.service.DirectorySyncService;
import com.winllc.certalert.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * Fills a demo's tables before anybody looks at them.
 *
 * <p>A sweep is a write, and a demo refuses every write a visitor asks for - including the
 * one that would give it something to show. Left to the schedule, a demo started this
 * morning has empty tables until the small hours, which is the whole application
 * demonstrating nothing.
 *
 * <p>So it sweeps once at startup, and only when there is nothing cached. Started against
 * a database that already holds a directory - a demo that has been restarted - it leaves it
 * alone and comes up immediately.
 *
 * <p>The expiry round-up runs after the sweep for the same reason. Notifications are
 * written when a sweep sees a certificate change state, and on a first sweep nothing has
 * changed state - everything is simply new - so the notifications page of a freshly
 * started demo says there is nothing to report while the tables behind it are full of
 * things expiring this week. The round-up is what gathers those, and it is the one thing
 * on that page a visitor cannot press for themselves.
 *
 * <p>It also creates the one project the demo needs. Running a project is a role the
 * sign-in page offers, and unlike the other three it is not something a directory entry
 * can carry: projects are this application's own data, granted by an administrator. With
 * no project, signing in as the project administrator would show the same pages as the
 * reader and the page would be describing a role that does not exist here.
 *
 * <p>This is the application's own doing rather than a visitor's, which is the line the
 * read-only rule draws: nothing anybody does to a demo changes it.
 */
@Component
@Conditional(DemoMode.On.class)
public class DemoDirectoryLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDirectoryLoader.class);

    private final DirectorySyncService syncService;
    private final NotificationService notifications;
    private final DirectoryUserRepository users;
    private final DemoProject demoProject;

    public DemoDirectoryLoader(
            DirectorySyncService syncService,
            NotificationService notifications,
            DirectoryUserRepository users,
            DemoProject demoProject) {
        this.syncService = syncService;
        this.notifications = notifications;
        this.users = users;
        this.demoProject = demoProject;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            log.info("Demo: the cache already holds {} person(s); not sweeping", users.count());
            return;
        }
        log.info("Demo: sweeping the directory once, so there is something to show");
        try {
            syncService.syncUsers();
            syncService.syncServers();
        } catch (RuntimeException e) {
            // A demo with empty tables is a poor demo; a demo that will not start is none
            // at all. Whatever is wrong with the directory, the pages still serve.
            log.warn("Demo: could not sweep the directory: {}", e.toString());
            return;
        }
        try {
            demoProject.seed();
        } catch (RuntimeException e) {
            log.warn("Demo: could not create the project: {}", e.toString());
        }
        try {
            // A real round-up, not a rehearsal: a rehearsal deliberately writes nothing,
            // and what this is for is the rows. Sending is off in the demo profile, so it
            // gathers and goes nowhere.
            log.info("Demo: running the expiry round-up, so the notifications page has something on it");
            notifications.digest();
        } catch (RuntimeException e) {
            log.warn("Demo: could not run the round-up: {}", e.toString());
        }
    }

}
