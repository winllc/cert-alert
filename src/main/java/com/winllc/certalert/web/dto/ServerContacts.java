package com.winllc.certalert.web.dto;

import java.util.List;

/**
 * A server's points of contact, from both places they come from.
 *
 * @param directory what the directory publishes in {@code serverPOC}: a cached copy of
 *     somebody else's data, replaced by every sweep, and so not editable here
 * @param managed what was added here, which a sweep leaves alone
 * @param editable whether the person asking may change the managed list, so the UI can
 *     show the controls rather than offer them and then refuse
 */
public record ServerContacts(List<String> directory, List<ServerContactRow> managed, boolean editable) {}
