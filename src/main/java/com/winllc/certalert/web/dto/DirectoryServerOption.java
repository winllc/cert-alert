package com.winllc.certalert.web.dto;

import com.winllc.certalert.domain.DirectoryServer;

/** A server as a picker shows it: enough to recognise, and the id to send back. */
public record DirectoryServerOption(Long id, String commonName, String serverUrl, String dutyOrganization) {

    public static DirectoryServerOption from(DirectoryServer server) {
        return new DirectoryServerOption(
                server.getId(), server.getCommonName(), server.getServerUrl(), server.getDutyOrganization());
    }
}
