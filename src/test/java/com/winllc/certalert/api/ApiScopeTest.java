package com.winllc.certalert.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ApiScopeTest {

    @Test
    void adminImpliesEverythingAndJobsRunImpliesJobsRead() {
        assertThat(ApiScope.expand(Set.of(ApiScope.ADMIN))).containsExactlyInAnyOrder(ApiScope.values());
        assertThat(ApiScope.expand(Set.of(ApiScope.JOBS_RUN))).containsExactlyInAnyOrder(ApiScope.JOBS_RUN, ApiScope.JOBS_READ);
        assertThat(ApiScope.expand(Set.of(ApiScope.CERTIFICATES_READ))).containsExactly(ApiScope.CERTIFICATES_READ);
    }

    @Test
    void aScopeNeedsItsRole() {
        Set<ApiScope.Role> reader = EnumSet.of(ApiScope.Role.USER);
        Set<ApiScope.Role> auditor = EnumSet.of(ApiScope.Role.USER, ApiScope.Role.AUDITOR);
        Set<ApiScope.Role> admin = EnumSet.of(ApiScope.Role.USER, ApiScope.Role.ADMIN);
        assertThat(ApiScope.CERTIFICATES_READ.allowedFor(reader)).isTrue();
        assertThat(ApiScope.JOBS_READ.allowedFor(reader)).isFalse();
        assertThat(ApiScope.JOBS_READ.allowedFor(auditor)).isTrue();
        assertThat(ApiScope.JOBS_READ.allowedFor(admin)).isTrue();
        assertThat(ApiScope.JOBS_RUN.allowedFor(auditor)).isFalse();
        assertThat(ApiScope.ADMIN.allowedFor(admin)).isTrue();
        assertThat(ApiScope.CERTIFICATES_READ.allowedFor(EnumSet.noneOf(ApiScope.Role.class))).isFalse();
    }

    @Test
    void scopesReadAndWriteAsTheirIds() {
        assertThat(ApiScope.parse("jobs:run, Certificates:Read")).containsExactly(ApiScope.JOBS_RUN, ApiScope.CERTIFICATES_READ);
        assertThat(ApiScope.format(Set.of(ApiScope.JOBS_RUN, ApiScope.CERTIFICATES_READ))).isEqualTo("certificates:read,jobs:run");
        assertThatThrownBy(() -> ApiScope.of("root")).isInstanceOf(IllegalArgumentException.class);
    }
}
