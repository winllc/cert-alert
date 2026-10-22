package com.winllc.certalert.export;

import com.winllc.certalert.config.BannerProperties;
import com.winllc.certalert.inventory.CertificateFilter;
import com.winllc.certalert.inventory.CertificateInventory;
import com.winllc.certalert.inventory.CertificateInventoryRow;
import com.winllc.certalert.license.Licensing;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * The certificate inventory as a file: the rows the same filter shows on the page and
 * through the API, as CSV or an Excel workbook, streamed from a database cursor.
 */
@Service
public class CertificateExport {

    /** A CSV export's media type. A constant, so a download's type never depends on the request. */
    public static final String CSV_CONTENT_TYPE = "text/csv;charset=UTF-8";

    /** An Excel export's media type. */
    public static final String XLSX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** What a file can be. */
    public enum Format {
        CSV(CSV_CONTENT_TYPE, "csv"),
        XLSX(XLSX_CONTENT_TYPE, "xlsx");

        private final String contentType;
        private final String extension;

        Format(String contentType, String extension) {
            this.contentType = contentType;
            this.extension = extension;
        }

        public String contentType() {
            return contentType;
        }

        public String extension() {
            return extension;
        }

        public static Format of(String value) {
            try {
                return valueOf((value == null ? "csv" : value).trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("An export is csv or xlsx");
            }
        }
    }

    static final List<String> COLUMNS = List.of(
            "Owner type", "Owner", "Owner DN", "Owner email", "Points of contact", "Subject DN", "Issuer DN",
            "Serial number", "SHA-256 fingerprint", "Not before (UTC)", "Not after (UTC)", "Days until expiry",
            "Status", "Key algorithm", "Key size", "Signature algorithm", "Hash algorithm",
            "Subject alternative names", "Use", "Revocation", "Revoked at (UTC)", "Risks");

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final CertificateInventory inventory;
    private final BannerProperties banner;
    private final Licensing licensing;
    private final Clock clock;

    public CertificateExport(CertificateInventory inventory, BannerProperties banner, Licensing licensing, Clock clock) {
        this.inventory = inventory;
        this.banner = banner;
        this.licensing = licensing;
        this.clock = clock;
    }

    /** {@code certalert-certificates-20261021-142233.xlsx}. */
    public String fileName(Format format) {
        return "certalert-certificates-" + FILE_STAMP.format(Instant.now(clock)) + "." + format.extension();
    }

    /** The classification marking, where banners are on and licensed; null otherwise. */
    public String marking() {
        return banner.isVisible() && licensing.shows("classification-banners") ? banner.getText() : null;
    }

    /**
     * Writes every matching certificate, at most {@code limit}, and says how many.
     *
     * @param by who asked, for the About sheet
     */
    public long write(CertificateFilter filter, Sort sort, Format format, long limit, String by, OutputStream out)
            throws IOException {
        Map<String, String> about = new LinkedHashMap<>();
        about.put("Exported", Instant.now(clock).toString());
        about.put("By", by == null ? "" : by);
        Map<String, Object> described = filter == null ? Map.of() : filter.describe();
        about.put("Filters", described.isEmpty() ? "none"
                : described.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("; ")));
        about.put("Order", sort.toString());
        TableWriter writer = format == Format.XLSX ? new XlsxTableWriter(out, about, marking()) : new CsvTableWriter(out);
        writer.header(COLUMNS);
        long written;
        try {
            written = inventory.forEach(filter, sort, limit, row -> {
                try {
                    writer.row(cells(row));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        writer.close();
        return written;
    }

    static List<TableWriter.Cell> cells(CertificateInventoryRow row) {
        return java.util.Arrays.asList(
                TableWriter.text(row.ownerType() == null ? null : row.ownerType().name()),
                TableWriter.text(row.ownerName()),
                TableWriter.text(row.ownerDn()),
                TableWriter.text(row.ownerEmail()),
                TableWriter.text(row.pointsOfContact()),
                TableWriter.text(row.subjectDn()),
                TableWriter.text(row.issuerDn()),
                TableWriter.text(row.serialNumber()),
                TableWriter.text(row.sha256Fingerprint()),
                TableWriter.time(row.notBefore()),
                TableWriter.time(row.notAfter()),
                TableWriter.number(row.daysUntilExpiry()),
                TableWriter.text(row.status() == null ? null : row.status().name()),
                TableWriter.text(row.keyAlgorithm()),
                TableWriter.number(row.keySize()),
                TableWriter.text(row.signatureAlgorithm()),
                TableWriter.text(row.hashAlgorithm()),
                TableWriter.text(row.subjectAlternativeNames()),
                TableWriter.text(row.use()),
                TableWriter.text(row.revocationStatus()),
                TableWriter.time(row.revokedAt()),
                TableWriter.text(row.risks().isEmpty() ? null : String.join(" ", row.risks())));
    }
}
