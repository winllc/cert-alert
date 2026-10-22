package com.winllc.certalert.export;

import com.winllc.certalert.audit.AuditContext;
import com.winllc.certalert.audit.AuditLog;
import com.winllc.certalert.audit.AuditRecord;
import com.winllc.certalert.inventory.CertificateFilter;
import com.winllc.certalert.inventory.CertificateInventory;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The certificate inventory as a download - the same filters as the page and the list, so
 * what is exported is what was on screen, without the paging. Anyone signed in, as the
 * inventory is; with a token, {@code certificates:read}.
 *
 * <p>A POST, because it writes: it is recorded as {@code inventory.exported}, with the format,
 * the filters and how many rows match, before a byte is sent. From the page it is a form post
 * carrying the CSRF token, so another site cannot make a signed-in browser export (and leave
 * that in the audit trail); with a token, the filters go in the query string or a form body.
 *
 * <p>The response is always a file: its type is a constant for the format, set before the
 * body, never HTML whatever the filters say; {@code attachment}, and not to be sniffed.
 */
@RestController
public class CertificateExportController {

    public static final String PATH = "/api/v1/certificates/export";

    private final CertificateExport export;
    private final CertificateInventory inventory;
    private final ExportProperties properties;
    private final AuditLog audit;

    public CertificateExportController(
            CertificateExport export, CertificateInventory inventory, ExportProperties properties, AuditLog audit) {
        this.export = export;
        this.inventory = inventory;
        this.properties = properties;
        this.audit = audit;
    }

    @PostMapping(PATH)
    public void download(
            CertificateFilter filter,
            @RequestParam(defaultValue = "csv") String format,
            @RequestParam(required = false) String sort,
            Authentication authentication,
            HttpServletResponse response) throws IOException {
        CertificateExport.Format chosen = CertificateExport.Format.of(format);
        Sort order = CertificateInventory.sort(sort);
        long matching = inventory.count(filter);
        long limit = Math.min(matching, properties.getMaxRows());
        audit.record(AuditRecord.of(AuditRecord.Category.DATA, "inventory.exported")
                .detail("format", chosen.extension())
                .detail("filter", filter.describe().toString())
                .detail("count", limit)
                .detail("matching", matching));

        response.setStatus(HttpStatus.OK.value());
        if (chosen == CertificateExport.Format.XLSX) {
            response.setContentType(CertificateExport.XLSX_CONTENT_TYPE);
        } else {
            response.setContentType(CertificateExport.CSV_CONTENT_TYPE);
        }
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + export.fileName(chosen) + "\"");
        response.setHeader("Cache-Control", "no-store");
        if (matching > limit) {
            response.setHeader("X-CertAlert-Truncated", "true");
        }
        export.write(filter, order, chosen, Math.max(limit, 0), AuditContext.actorOf(authentication).id(),
                response.getOutputStream());
        response.flushBuffer();
    }
}
