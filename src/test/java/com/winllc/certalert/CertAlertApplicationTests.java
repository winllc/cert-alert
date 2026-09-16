package com.winllc.certalert;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.certalert.service.CertificateMonitorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class CertAlertApplicationTests {

    @Autowired
    private CertificateMonitorService monitorService;

    @Test
    void contextLoads() {
        assertThat(monitorService).isNotNull();
    }
}
