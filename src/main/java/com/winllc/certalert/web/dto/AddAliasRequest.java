package com.winllc.certalert.web.dto;

import com.winllc.certalert.domain.UserEmailAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * An address to add to a person.
 *
 * @param address the address itself
 * @param kind PERSONAL when omitted; GROUP for a list
 * @param label what it is, in somebody's words
 */
public record AddAliasRequest(
        @NotBlank @Size(max = 320) String address, UserEmailAlias.Kind kind, @Size(max = 255) String label) {}
