package com.winllc.certalert;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.certalert.service.DirectorySyncService;
import com.winllc.certalert.web.DirectoryDataTablesController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class CertAlertApplicationTests {

    @Autowired
    private DirectorySyncService syncService;

    @Autowired
    private DirectoryDataTablesController dataTablesController;

    @Test
    void contextLoads() {
        assertThat(syncService).isNotNull();
        assertThat(dataTablesController).isNotNull();
    }
}
