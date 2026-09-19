package com.winllc.certalert.web.dto;

import java.util.List;

/**
 * What a server holds for one attribute, in full.
 *
 * <p>The whole set rather than one value at a time: the control on the page edits the
 * attribute as a whole, so an empty list is how it is cleared.
 */
public record ServerAttributeValuesRequest(List<String> values) {}
