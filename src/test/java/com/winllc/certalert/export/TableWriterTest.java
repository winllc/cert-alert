package com.winllc.certalert.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What a spreadsheet would run is written as text, in both formats; numbers and dates stay typed. */
class TableWriterTest {

    @Test
    void everyFormulaPrefixIsNeutralised() {
        for (String hostile : List.of("=1+1", "+1+1", "-1+1", "@SUM(A1)", "\t=1", "\r=1")) {
            assertThat(TableWriter.neutralise(hostile)).as(hostile).startsWith("'");
        }
        assertThat(TableWriter.neutralise("CN=web01")).isEqualTo("CN=web01");
        // Spreadsheets skip leading spaces, so a formula behind them is still a formula.
        for (String hidden : List.of(" =1+1", "  +1", "\n-1", "\u00a0@SUM(A1)", " \t=1")) {
            assertThat(TableWriter.neutralise(hidden)).as(hidden).startsWith("'");
        }
        assertThat(TableWriter.neutralise("  plain")).isEqualTo("  plain");
        assertThat(TableWriter.neutralise("   ")).isEqualTo("   ");
        assertThat(TableWriter.neutralise("")).isEmpty();
        assertThat(CsvTableWriter.format(TableWriter.number(-5))).isEqualTo("-5");
        assertThat(CsvTableWriter.format(TableWriter.text("-5"))).isEqualTo("'-5");
        assertThat(CsvTableWriter.format(TableWriter.text("a,\"b\""))).isEqualTo("\"a,\"\"b\"\"\"");
        assertThat(CsvTableWriter.format(TableWriter.time(Instant.parse("2026-10-21T00:00:00Z")))).isEqualTo("2026-10-21T00:00:00Z");
    }

    @Test
    void anXlsxCellCarriesNeitherAFormulaNorACharacterXmlCannotHold() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XlsxTableWriter writer = new XlsxTableWriter(out, Map.of("By", "test"), "UNCLASSIFIED//FOUO")) {
            writer.header(List.of("A", "B", "C"));
            writer.row(Arrays.asList(TableWriter.text("@cmd\u0001<x>&"), TableWriter.number(42), TableWriter.time(Instant.EPOCH)));
            writer.row(Arrays.asList(null, TableWriter.text("plain"), null));
        }
        String zip = out.toString(StandardCharsets.ISO_8859_1);
        assertThat(zip).startsWith("PK");
        assertThat(XlsxTableWriter.escape("@cmd\u0001<x>&")).isEqualTo("@cmd&lt;x&gt;&amp;");
        assertThat(XlsxTableWriter.reference(0, 1)).isEqualTo("A1");
        assertThat(XlsxTableWriter.reference(26, 7)).isEqualTo("AA7");
        assertThat(XlsxTableWriter.serial(Instant.parse("1970-01-01T00:00:00Z"))).isEqualTo(25569.0);
    }
}
