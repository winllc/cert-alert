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
     * What to call the visitor in the account menu.
     *
     * <p>Says what they are rather than pretending to be somebody: a demo that greets
     * everybody as "Alice Archer" invites the question of who else is looking.
     */
    private String visitor = "Demo visitor";

    /**
     * An identifier the sample directory knows, or empty.
     *
     * <p>A few things in the UI are about the person reading the page - the servers they
     * are a point of contact for, their own notifications - and with nobody signed in they
     * have nothing to show. Naming one of the sample's people here gives the demo somebody
     * to be for those, without that person being able to change anything either.
     */
    private String signedInAs = "";

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

    public String getVisitor() {
        return visitor;
    }

    public void setVisitor(String visitor) {
        this.visitor = visitor;
    }

    public String getSignedInAs() {
        return signedInAs;
    }

    public void setSignedInAs(String signedInAs) {
        this.signedInAs = signedInAs == null ? "" : signedInAs.trim();
    }
}
