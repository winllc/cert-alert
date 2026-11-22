package com.winllc.certalert.web;

import java.util.Map;
import org.springframework.data.jpa.datatables.mapping.DataTablesInput;
import org.springframework.data.jpa.datatables.mapping.DataTablesOutput;

/**
 * What every search table's endpoint does around the DataTables library's {@code findAll}.
 *
 * <p>Before it, the page length is held to what a page is: the library reads -1 as "every
 * row", which would load, map and serialise a whole table in one request. After it, a query
 * the library could not answer is thrown rather than returned: the library catches every
 * exception and puts its text - entity names, SQL, the driver's message - in the response's
 * {@code error}, with a 200. Thrown, it reaches {@link GlobalExceptionHandler}, which logs it
 * and answers a plain 500.
 */
final class DataTablesRequests {

    private DataTablesRequests() {}

    /**
     * @param maxLength the most rows one page may ask for ({@code cert-alert.api.max-page-size})
     * @throws IllegalArgumentException for a length below 1, or SearchPanes, which no table here uses
     */
    static DataTablesInput bounded(DataTablesInput input, int maxLength) {
        Integer length = input.getLength();
        if (length == null || length < 1) {
            throw new IllegalArgumentException("A table page has to ask for at least one row");
        }
        if (length > maxLength) {
            input.setLength(maxLength);
        }
        Map<String, ?> panes = input.getSearchPanes();
        if (panes != null && !panes.isEmpty()) {
            throw new IllegalArgumentException("SearchPanes are not supported");
        }
        return input;
    }

    /** The output, unless the library caught a failure and put it in {@code error}. */
    static <T> DataTablesOutput<T> checked(DataTablesOutput<T> output) {
        if (output.getError() != null) {
            throw new IllegalStateException("The table could not be read: " + output.getError());
        }
        return output;
    }
}
