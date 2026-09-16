package com.winllc.certalert.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.winllc.certalert.domain.CertificateTarget;
import com.winllc.certalert.service.CertificateMonitorService;
import com.winllc.certalert.service.CertificateSweepService;
import com.winllc.certalert.service.CertificateTargetService;
import com.winllc.certalert.service.DuplicateTargetException;
import com.winllc.certalert.service.ResourceNotFoundException;
import com.winllc.certalert.service.SweepResult;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CertificateTargetController.class)
class CertificateTargetControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CertificateTargetService targetService;

    @MockitoBean
    private CertificateMonitorService monitorService;

    @MockitoBean
    private CertificateSweepService sweepService;

    @Test
    void listReturnsTargets() throws Exception {
        when(targetService.findAll()).thenReturn(List.of(target(1L, "prod-api", "api.example.com")));

        mockMvc.perform(get("/api/v1/targets"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("prod-api"))
                .andExpect(jsonPath("$[0].hostname").value("api.example.com"))
                .andExpect(jsonPath("$[0].port").value(443));
    }

    @Test
    void createReturnsLocationHeader() throws Exception {
        when(targetService.create(anyString(), anyString(), anyInt(), any(), anyBoolean()))
                .thenReturn(target(7L, "prod-api", "api.example.com"));

        mockMvc.perform(post("/api/v1/targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"prod-api","hostname":"api.example.com","port":443}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/targets/7"))
                .andExpect(jsonPath("$.id").value(7));
    }

    @Test
    void createRejectsBlankNameWithProblemDetail() throws Exception {
        mockMvc.perform(post("/api/v1/targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"","hostname":"api.example.com"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.errors.name").exists());
    }

    @Test
    void createRejectsOutOfRangePort() throws Exception {
        mockMvc.perform(post("/api/v1/targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"bad-port","hostname":"api.example.com","port":70000}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.port").exists());
    }

    @Test
    void duplicateNameIsReportedAsConflict() throws Exception {
        when(targetService.create(anyString(), anyString(), anyInt(), any(), anyBoolean()))
                .thenThrow(new DuplicateTargetException("prod-api"));

        mockMvc.perform(post("/api/v1/targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"prod-api","hostname":"api.example.com"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Duplicate target"));
    }

    @Test
    void unknownTargetIsReportedAsNotFound() throws Exception {
        when(targetService.findById(42L)).thenThrow(ResourceNotFoundException.target(42L));

        mockMvc.perform(get("/api/v1/targets/42"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource not found"))
                .andExpect(jsonPath("$.detail").value("No certificate target with id 42"));
    }

    @Test
    void deleteReturnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/v1/targets/3")).andExpect(status().isNoContent());

        verify(targetService).delete(3L);
    }

    @Test
    void deleteOfUnknownTargetIsReportedAsNotFound() throws Exception {
        doThrow(ResourceNotFoundException.target(3L)).when(targetService).delete(anyLong());

        mockMvc.perform(delete("/api/v1/targets/3")).andExpect(status().isNotFound());
    }

    @Test
    void sweepEndpointReturnsSummary() throws Exception {
        when(sweepService.sweep()).thenReturn(new SweepResult(5, 2, 1, Duration.ofMillis(1500)));

        mockMvc.perform(post("/api/v1/targets/check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checked").value(5))
                .andExpect(jsonPath("$.unhealthy").value(2))
                .andExpect(jsonPath("$.errored").value(1))
                .andExpect(jsonPath("$.durationMillis").value(1500));
    }

    /** Builds a persisted-looking target; the id is only ever set by JPA in production. */
    private CertificateTarget target(Long id, String name, String hostname) throws Exception {
        CertificateTarget target = new CertificateTarget(name, hostname, 443);
        Field idField = CertificateTarget.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(target, id);
        return target;
    }
}
