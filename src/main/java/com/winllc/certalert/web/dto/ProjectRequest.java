package com.winllc.certalert.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Creating or renaming a project. */
public record ProjectRequest(@NotBlank @Size(max = 255) String name, @Size(max = 2000) String description) {}
