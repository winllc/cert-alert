package com.winllc.certalert.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Payload for creating or replacing a monitored target. */
public record CertificateTargetRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 255) String hostname,
        @Min(1) @Max(65535) Integer port,
        @Size(max = 1000) String description,
        Boolean enabled) {

    private static final int DEFAULT_PORT = 443;

    public int portOrDefault() {
        return port == null ? DEFAULT_PORT : port;
    }

    public boolean enabledOrDefault() {
        return enabled == null || enabled;
    }
}
