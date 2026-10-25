package com.winllc.certalert;

import java.util.Collection;
import java.util.stream.Collectors;

/**
 * Values from outside - an identity provider's claims, a presented certificate's names, a
 * receiver's error text - made safe to put in a log line: on one line, so nothing a value holds
 * can start a forged log line of its own on the plain console pattern, and with no other
 * control characters (terminal escapes). The ECS JSON format escapes them anyway; this is for
 * the default pattern.
 */
public final class LogSafe {

    private LogSafe() {}

    /** The value's text, its line breaks and tabs as spaces and any other control character as {@code ?}; null stays null. */
    public static String oneLine(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Collection<?> values) {
            return values.stream().map(LogSafe::oneLine).collect(Collectors.joining(", ", "[", "]"));
        }
        // \R first: every line break, CR LF as one, including the Unicode ones (NEL, LS, PS).
        return value.toString().replaceAll("\\R", " ").replace('\t', ' ').replaceAll("\\p{Cntrl}", "?");
    }
}
