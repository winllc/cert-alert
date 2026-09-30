package com.winllc.certalert.demo;

import java.util.List;

/**
 * The accounts a demo offers, and what each one is for.
 *
 * <p>One list, read by the three things that have to agree about it: the generator, which
 * puts these people in the directory; whatever makes each role real, which is an
 * administrator identifier for one and a project for another; and the sign-in page, which
 * prints them so a visitor can be each of them in turn.
 *
 * <p>Roles are the point. What this application shows somebody depends on what they have
 * to do with the directory - an administrator sees the whole audit trail, a point of
 * contact sees the servers they answer for, and somebody who is neither sees the tables and
 * little else. A demo with one account demonstrates one of those and quietly implies the
 * rest work.
 *
 * <p>Stable uids, unlike the generated crowd around them, whose names shift with the size
 * of the directory. These four are printed on a page and typed by a person, so they cannot
 * move.
 */
public final class DemoAccounts {

    /**
     * An account on the sign-in page.
     *
     * @param role what to call it
     * @param uid what they type, and their entry's uid
     * @param email the address the directory holds, which is what names them elsewhere
     * @param displayName the name shown once signed in
     * @param sees one line on what this role gets that the others do not
     */
    public record Account(String role, String uid, String email, String displayName, String sees) {}

    public static final Account ADMINISTRATOR = new Account(
            "Administrator",
            "demo.admin",
            "demo.admin@intelink.ic.gov",
            "Dana Okafor",
            "Everything, plus the administration page: the whole audit trail, the managed "
                    + "attributes, and the controls that run the jobs.");

    public static final Account PROJECT_ADMINISTRATOR = new Account(
            "Project administrator",
            "demo.project",
            "demo.project@intelink.ic.gov",
            "Priya Raman",
            "Runs the Mission Systems project, so they manage the points of contact for "
                    + "every server in it \u2014 but not the administration page.");

    public static final Account POINT_OF_CONTACT = new Account(
            "Point of contact",
            "demo.contact",
            "demo.contact@intelink.ic.gov",
            "Marcus Bell",
            "Answers for a handful of servers: their own notifications, and the contact "
                    + "lists of the servers they are named on.");

    public static final Account READER = new Account(
            "Reader",
            "demo.reader",
            "demo.reader@intelink.ic.gov",
            "Sam Whitfield",
            "Signed in and nothing more. The tables and the details pages, with none of "
                    + "the controls the others get.");

    /** In the order the sign-in page lists them: most to least. */
    public static final List<Account> ALL =
            List.of(ADMINISTRATOR, PROJECT_ADMINISTRATOR, POINT_OF_CONTACT, READER);

    /** The project the project administrator runs, created after the first sweep. */
    public static final String PROJECT_NAME = "Mission Systems";

    private DemoAccounts() {}
}
