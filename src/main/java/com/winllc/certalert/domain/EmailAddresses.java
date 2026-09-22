package com.winllc.certalert.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What counts as an email address here, and how to get several out of one directory value.
 *
 * <p>One place rather than three, because three copies of a pattern drift: a contact added
 * by hand, an address a person answers to, and a scraped {@code serverPOC} all have to
 * agree about what an address is, or a value accepted by one path is rejected by another
 * and the reason is nowhere obvious.
 */
public final class EmailAddresses {

    /**
     * Deliberately loose. This is not the place to decide what a valid address is - a
     * directory holds addresses in forms no pattern here should be rejecting - so it only
     * rules out what is obviously not one: no at-sign, no dot after it, or whitespace
     * anywhere.
     */
    private static final Pattern ADDRESS = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private EmailAddresses() {}

    /** Whether this is something to send mail to. */
    public static boolean isAddress(String value) {
        return value != null && ADDRESS.matcher(value.trim()).matches();
    }

    /** Trimmed and lowercased, which is how every address is stored and compared here. */
    public static String normalise(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * The addresses packed into one directory value.
     *
     * <p>An attribute is multi-valued, so a directory holding three contacts for a server
     * <em>can</em> carry three values - but plenty carry one value with the three written
     * out comma-separated, because that is what somebody typed into a form. Read whole,
     * such a value matches nobody at all: it is not an address and it is not anybody's
     * name. Split on commas, and the ordinary single-address value comes back unchanged
     * because it contains none.
     *
     * <p>Whitespace around a separator is not a separator, and an empty stretch between
     * two commas is nothing rather than an empty contact.
     */
    public static List<String> split(String value) {
        List<String> found = new ArrayList<>();
        if (value == null) {
            return found;
        }
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                found.add(trimmed);
            }
        }
        return found;
    }
}
