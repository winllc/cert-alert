package com.winllc.certalert.web;

import com.winllc.certalert.domain.CertificateCheck;
import com.winllc.certalert.domain.CertificateTarget;
import com.winllc.certalert.service.CertificateMonitorService;
import com.winllc.certalert.service.CertificateSweepService;
import com.winllc.certalert.service.CertificateTargetService;
import com.winllc.certalert.web.dto.CertificateCheckResponse;
import com.winllc.certalert.web.dto.CertificateTargetRequest;
import com.winllc.certalert.web.dto.CertificateTargetResponse;
import com.winllc.certalert.web.dto.SweepResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/** REST API over monitored certificate targets. */
@RestController
@RequestMapping("/api/v1/targets")
public class CertificateTargetController {

    private static final int MAX_PAGE_SIZE = 100;

    private final CertificateTargetService targetService;
    private final CertificateMonitorService monitorService;
    private final CertificateSweepService sweepService;

    public CertificateTargetController(
            CertificateTargetService targetService,
            CertificateMonitorService monitorService,
            CertificateSweepService sweepService) {
        this.targetService = targetService;
        this.monitorService = monitorService;
        this.sweepService = sweepService;
    }

    @GetMapping
    public List<CertificateTargetResponse> list() {
        return targetService.findAll().stream().map(CertificateTargetResponse::from).toList();
    }

    @GetMapping("/{id}")
    public CertificateTargetResponse get(@PathVariable Long id) {
        return CertificateTargetResponse.from(targetService.findById(id));
    }

    @PostMapping
    public ResponseEntity<CertificateTargetResponse> create(
            @Valid @RequestBody CertificateTargetRequest request, UriComponentsBuilder uriBuilder) {
        CertificateTarget created = targetService.create(
                request.name(),
                request.hostname(),
                request.portOrDefault(),
                request.description(),
                request.enabledOrDefault());
        URI location = uriBuilder.path("/api/v1/targets/{id}").buildAndExpand(created.getId()).toUri();
        return ResponseEntity.created(location).body(CertificateTargetResponse.from(created));
    }

    @PutMapping("/{id}")
    public CertificateTargetResponse update(
            @PathVariable Long id, @Valid @RequestBody CertificateTargetRequest request) {
        CertificateTarget updated = targetService.update(
                id,
                request.name(),
                request.hostname(),
                request.portOrDefault(),
                request.description(),
                request.enabledOrDefault());
        return CertificateTargetResponse.from(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        targetService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Checks one target immediately, bypassing the schedule. */
    @PostMapping("/{id}/check")
    public CertificateCheckResponse check(@PathVariable Long id) {
        return CertificateCheckResponse.from(monitorService.checkTarget(id));
    }

    /** Checks every enabled target immediately. */
    @PostMapping("/check")
    public SweepResponse checkAll() {
        return SweepResponse.from(sweepService.sweep());
    }

    @GetMapping("/{id}/checks")
    public Page<CertificateCheckResponse> history(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
        Page<CertificateCheck> checks = targetService.findChecks(id, pageable);
        return checks.map(CertificateCheckResponse::from);
    }
}
