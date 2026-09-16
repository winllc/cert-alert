package com.winllc.certalert.service;

import com.winllc.certalert.domain.CertificateCheck;
import com.winllc.certalert.domain.CertificateTarget;
import com.winllc.certalert.repository.CertificateCheckRepository;
import com.winllc.certalert.repository.CertificateTargetRepository;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CRUD over monitored targets, plus access to their check history. */
@Service
@Transactional(readOnly = true)
public class CertificateTargetService {

    private final CertificateTargetRepository targetRepository;
    private final CertificateCheckRepository checkRepository;

    public CertificateTargetService(
            CertificateTargetRepository targetRepository, CertificateCheckRepository checkRepository) {
        this.targetRepository = targetRepository;
        this.checkRepository = checkRepository;
    }

    public List<CertificateTarget> findAll() {
        return targetRepository.findAll();
    }

    public CertificateTarget findById(Long id) {
        return targetRepository.findById(id).orElseThrow(() -> ResourceNotFoundException.target(id));
    }

    public Page<CertificateCheck> findChecks(Long targetId, Pageable pageable) {
        requireExists(targetId);
        return checkRepository.findByTargetIdOrderByCheckedAtDesc(targetId, pageable);
    }

    @Transactional
    public CertificateTarget create(String name, String hostname, int port, String description, boolean enabled) {
        if (targetRepository.existsByNameIgnoreCase(name)) {
            throw new DuplicateTargetException(name);
        }
        CertificateTarget target = new CertificateTarget(name, hostname, port);
        target.setDescription(description);
        target.setEnabled(enabled);
        return targetRepository.save(target);
    }

    @Transactional
    public CertificateTarget update(Long id, String name, String hostname, int port, String description, boolean enabled) {
        CertificateTarget target = findById(id);
        if (!target.getName().equalsIgnoreCase(name) && targetRepository.existsByNameIgnoreCase(name)) {
            throw new DuplicateTargetException(name);
        }
        target.setName(name);
        target.setHostname(hostname);
        target.setPort(port);
        target.setDescription(description);
        target.setEnabled(enabled);
        return targetRepository.save(target);
    }

    @Transactional
    public void delete(Long id) {
        requireExists(id);
        targetRepository.deleteById(id);
    }

    private void requireExists(Long id) {
        if (!targetRepository.existsById(id)) {
            throw ResourceNotFoundException.target(id);
        }
    }
}
