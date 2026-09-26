package com.winllc.certalert.demo;

import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.service.DirectorySyncService;
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
 * <p>This is the application's own doing rather than a visitor's, which is the line the
 * read-only rule draws: nothing anybody does to a demo changes it.
 */
@Component
@Conditional(DemoMode.On.class)
public class DemoDirectoryLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDirectoryLoader.class);

    private final DirectorySyncService syncService;
    private final DirectoryUserRepository users;

    public DemoDirectoryLoader(DirectorySyncService syncService, DirectoryUserRepository users) {
        this.syncService = syncService;
        this.users = users;
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
        }
    }
}
