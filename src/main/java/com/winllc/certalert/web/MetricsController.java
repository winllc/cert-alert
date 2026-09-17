package com.winllc.certalert.web;

import com.winllc.certalert.service.MetricsService;
import com.winllc.certalert.web.dto.Metrics;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The numbers behind the metrics page. */
@RestController
@RequestMapping("/api/v1")
public class MetricsController {

    private final MetricsService metricsService;

    public MetricsController(MetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @GetMapping("/metrics")
    public Metrics metrics() {
        return metricsService.gather();
    }
}
