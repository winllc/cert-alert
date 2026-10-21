package com.winllc.certalert.export;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * An Excel workbook (Office Open XML, .xlsx), written as it goes.
 *
 * <p>Written directly rather than through a spreadsheet library: the format needed here is
 * a handful of fixed XML parts and one sheet of inline strings, numbers and dates, and the
 * libraries that do the general case bring a dependency tree - and a CVE history - this does
 * not need. The sheet is streamed into the zip a row at a time, so memory stays flat however
 * many rows there are.
 *
 * <p>The header row is frozen and bold. Text is inline strings (never shared-string formulas),
 * still given the leading apostrophe a formula-looking value gets in CSV, stripped of the
 * characters XML 1.0 cannot carry, and cut at Excel's 32,767-character limit. Instants are
 * date-time cells in UTC. A second sheet, "About", says what the export is: when, by whom,
 * with which filters, and the classification marking where one is configured - which is also
 * the printed page's header and footer.
 */
public final class XlsxTableWriter implements TableWriter {

    private static final int MAX_CELL = 32_767;

    private final ZipOutputStream zip;
    private final Writer sheet;
    private final Map<String, String> about;
    private final String marking;
    private int rows;
    private int columns;

    /**
     * @param about what the export is, as label and value, for the About sheet
     * @param marking the classification marking, or null for none
     */
    public XlsxTableWriter(OutputStream out, Map<String, String> about, String marking) throws IOException {
        this.zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
        this.about = about;
        this.marking = marking == null || marking.isBlank() ? null : marking;
        part("[Content_Types].xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">\
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>\
                <Default Extension="xml" ContentType="application/xml"/>\
                <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>\
                <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>\
                <Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>\
                <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>\
                </Types>""");
        part("_rels/.rels", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>\
                </Relationships>""");
        part("xl/workbook.xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" \
                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">\
                <sheets><sheet name="Certificates" sheetId="1" r:id="rId1"/><sheet name="About" sheetId="2" r:id="rId2"/></sheets>\
                </workbook>""");
        part("xl/_rels/workbook.xml.rels", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>\
                <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>\
                <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>\
                </Relationships>""");
        // Style 1: a UTC date-time. Style 2: the bold header.
        part("xl/styles.xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">\
                <numFmts count="1"><numFmt numFmtId="164" formatCode="yyyy-mm-dd hh:mm:ss"/></numFmts>\
                <fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>\
                <fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>\
                <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>\
                <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>\
                <cellXfs count="3"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>\
                <xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>\
                <xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs>\
                </styleSheet>""");
        zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
        this.sheet = new OutputStreamWriter(zip, StandardCharsets.UTF_8);
        sheet.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" "
                + "state=\"frozen\"/></sheetView></sheetViews><sheetData>");
    }

    @Override
    public void header(List<String> names) throws IOException {
        columns = names.size();
        StringBuilder row = new StringBuilder("<row r=\"").append(++rows).append("\">");
        for (int i = 0; i < names.size(); i++) {
            row.append("<c r=\"").append(reference(i, rows)).append("\" s=\"2\" t=\"inlineStr\"><is><t>")
                    .append(escape(names.get(i))).append("</t></is></c>");
        }
        sheet.write(row.append("</row>").toString());
    }

    @Override
    public void row(List<Cell> cells) throws IOException {
        StringBuilder row = new StringBuilder("<row r=\"").append(++rows).append("\">");
        for (int i = 0; i < cells.size(); i++) {
            Cell cell = cells.get(i);
            if (cell == null) {
                continue;
            }
            String ref = reference(i, rows);
            switch (cell) {
                case Text text -> row.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                        .append(escape(clip(TableWriter.neutralise(text.value())))).append("</t></is></c>");
                case Number number -> row.append("<c r=\"").append(ref).append("\"><v>")
                        .append(number.value()).append("</v></c>");
                case Time time -> row.append("<c r=\"").append(ref).append("\" s=\"1\"><v>")
                        .append(serial(time.value())).append("</v></c>");
            }
        }
        sheet.write(row.append("</row>").toString());
    }

    @Override
    public void close() throws IOException {
        StringBuilder end = new StringBuilder("</sheetData>");
        if (marking != null) {
            end.append(headerFooter());
        }
        end.append("</worksheet>");
        sheet.write(end.toString());
        sheet.flush();
        zip.closeEntry();

        StringBuilder second = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
        int r = 0;
        if (marking != null) {
            second.append(aboutRow(++r, "Classification", marking));
        }
        for (Map.Entry<String, String> entry : about.entrySet()) {
            second.append(aboutRow(++r, entry.getKey(), entry.getValue()));
        }
        second.append("</sheetData>");
        if (marking != null) {
            second.append(headerFooter());
        }
        second.append("</worksheet>");
        part("xl/worksheets/sheet2.xml", second.toString());
        zip.finish();
        zip.flush();
    }

    /** How many data rows have been written, the header aside. */
    public int rows() {
        return Math.max(0, rows - 1);
    }

    private String headerFooter() {
        String text = escape(marking.replace("&", "&&"));
        return "<headerFooter><oddHeader>&amp;C" + text + "</oddHeader><oddFooter>&amp;C" + text + "</oddFooter></headerFooter>";
    }

    private static String aboutRow(int r, String label, String value) {
        return "<row r=\"" + r + "\"><c r=\"A" + r + "\" s=\"2\" t=\"inlineStr\"><is><t>" + escape(label)
                + "</t></is></c><c r=\"B" + r + "\" t=\"inlineStr\"><is><t xml:space=\"preserve\">"
                + escape(clip(TableWriter.neutralise(value))) + "</t></is></c></row>";
    }

    private void part(String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /** Days since 1899-12-30, which is how Excel counts (the 1900 date system). */
    static double serial(Instant instant) {
        return instant.toEpochMilli() / 86_400_000.0 + 25_569.0;
    }

    /** A1-style reference: column letters, row number. */
    static String reference(int column, int row) {
        StringBuilder letters = new StringBuilder();
        int n = column + 1;
        while (n > 0) {
            int rem = (n - 1) % 26;
            letters.insert(0, (char) ('A' + rem));
            n = (n - 1) / 26;
        }
        return letters.append(row).toString();
    }

    private static String clip(String value) {
        return value.length() > MAX_CELL ? value.substring(0, MAX_CELL) : value;
    }

    /** XML-escaped, without the control characters XML 1.0 has no way to carry. */
    static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                default -> {
                    if (c == '\t' || c == '\n' || c == '\r' || (c >= 0x20 && c != 0xFFFE && c != 0xFFFF)) {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
