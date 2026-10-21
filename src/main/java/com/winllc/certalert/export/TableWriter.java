package com.winllc.certalert.export;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * A table written out one row at a time, as CSV or as an Excel workbook, so an export of any
 * size streams rather than being built in memory.
 *
 * <p>Text that a spreadsheet would read as a formula - starting with {@code =}, {@code +},
 * {@code -}, {@code @}, a tab or a carriage return - is written with a leading apostrophe,
 * in both formats: a certificate's subject, an owner's name and a contact's address are all
 * somebody else's text, and opening an export must not run it. Numbers and dates are typed
 * cells and written as they are.
 */
public interface TableWriter extends AutoCloseable {

    /** One cell: text, a number or an instant. Null is an empty cell. */
    sealed interface Cell permits Text, Number, Time {}

    record Text(String value) implements Cell {}

    record Number(java.lang.Number value) implements Cell {}

    record Time(Instant value) implements Cell {}

    static Cell text(String value) {
        return value == null ? null : new Text(value);
    }

    static Cell number(java.lang.Number value) {
        return value == null ? null : new Number(value);
    }

    static Cell time(Instant value) {
        return value == null ? null : new Time(value);
    }

    void header(List<String> columns) throws IOException;

    void row(List<Cell> cells) throws IOException;

    /** Finishes the file; nothing may be written after. */
    @Override
    void close() throws IOException;

    /** The leading apostrophe that keeps a spreadsheet from running text as a formula. */
    static String neutralise(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
    }
}
