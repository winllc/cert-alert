package com.winllc.certalert.web.dto;

import java.util.List;

/**
 * Every managed attribute, with what this server holds for each.
 *
 * @param editable whether the person asking may change them, so the page shows controls it
 *     will not then refuse
 */
public record ServerAttributes(List<ServerAttributeRow> attributes, boolean editable) {}
