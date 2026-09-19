package com.winllc.certalert.web.dto;

import java.util.List;

/**
 * Every managed attribute, with what this server's directory entry holds for each.
 *
 * @param editable whether the person asking may change them, so the page shows controls it
 *     will not then refuse
 * @param writable whether this application binds to the directory as anybody at all; an
 *     anonymous connection can read the entry and will not be writing to it
 * @param error what went wrong reading the entry, where something did: the directory is the
 *     source of these values, and a page that quietly showed none would be lying
 */
public record ServerAttributes(
        List<ServerAttributeRow> attributes, boolean editable, boolean writable, String error) {}
