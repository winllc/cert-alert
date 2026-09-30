package com.winllc.certalert.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * A running copy of this application that anybody can look at and nobody can change.
 *
 * <p>For showing the thing to people: a screen to walk somebody through, a link in a
 * proposal, a page open on a stand. Everything is visible, the administration pages
 * included, because what is being shown is what the application does - and nothing a
 * visitor does to it has any effect.
 *
 * <p>Read-only is enforced where it cannot be talked around: every request that is not a
 * plain read is refused before it reaches a controller. The buttons are still there and
 * still clickable, because a demo that hides half the product demonstrates half the
 * product; pressing one says plainly that this is a demo.
 *
 * <p><strong>Not a way to run this for real.</strong> It hands every visitor the
 * administrator's view of the whole directory with no sign-in at all, which is a
 * reasonable thing to do with invented data and never with somebody's real one. Point it
 * at a directory of generated entries, on an instance you can throw away.
 */
@Component
@ConfigurationProperties(prefix = "cert-alert.demo")
public class DemoProperties {

    /** Off unless asked for, in every profile. */
    private boolean enabled = false;

    /**
     * The password every demo account carries, printed on the sign-in page.
     *
     * <p>One password across all of them, because what a demo is showing is the roles, not
     * the passwords: four different secrets to mistype would be four ways to fail at the
     * only step before the thing you came to look at. It is not a credential - the accounts
     * it opens are invented and the directory holding them goes with the process.
     */
    private String password = "password";

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * Whether the demo brings a directory of its own.
     *
     * <p>On, which is what makes a demo something you can start with nothing else running.
     * Turn it off to demonstrate against a directory that is already there - the sample
     * one in the compose file, or a generated one loaded into a real server - in which
     * case {@code spring.ldap.urls} points at that and nothing here is generated.
     */
    private boolean generateDirectory = true;

    public boolean isGenerateDirectory() {
        return generateDirectory;
    }

    public void setGenerateDirectory(boolean generateDirectory) {
        this.generateDirectory = generateDirectory;
    }

    /**
     * How many people the generated directory holds, and how many servers.
     *
     * <p>Enough that the tables page, the filters have something to narrow and the metrics
     * have a shape - and small enough that a demo is serving pages a second or two after
     * it starts.
     */
    private int people = 24;

    private int servers = 16;

    /**
     * Fixes the generated directory, so a demo restarted an hour later is the same demo.
     *
     * <p>Only the choices are fixed: the dates are always measured from startup, because a
     * demo of an expiry tracker showing a directory that expired last spring is worse than
     * no demo.
     */
    private long seed = 20260101L;

    public int getPeople() {
        return people;
    }

    public void setPeople(int people) {
        this.people = people;
    }

    public int getServers() {
        return servers;
    }

    public void setServers(int servers) {
        this.servers = servers;
    }

    public long getSeed() {
        return seed;
    }

    public void setSeed(long seed) {
        this.seed = seed;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

}
