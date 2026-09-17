package com.winllc.certalert.web.dto;

import java.util.List;

/**
 * Every address a person answers to, from both places they come from.
 *
 * @param directory what the directory publishes: icEmail, internetEmail and the rest, a
 *     cached copy replaced by every sweep and so not editable here
 * @param added what was added here, which a sweep leaves alone
 * @param editable whether the person asking may change the added list
 */
public record UserAddresses(List<String> directory, List<UserEmailAliasRow> added, boolean editable) {}
