package com.winllc.certalert.export;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * RFC 4180 CSV, UTF-8 with a byte-order mark (without one, Excel reads UTF-8 as the local
 * code page), CRLF line ends, instants in ISO-8601 UTC.
 */
public final class CsvTableWriter implements TableWriter {

    private final Writer out;

    public CsvTableWriter(OutputStream stream) throws IOException {
        this.out = new OutputStreamWriter(stream, StandardCharsets.UTF_8);
        out.write('﻿');
    }

    @Override
    public void header(List<String> columns) throws IOException {
        line(columns.stream().map(TableWriter::text).toList());
    }

    @Override
    public void row(List<Cell> cells) throws IOException {
        line(cells);
    }

    private void line(List<Cell> cells) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                line.append(',');
            }
            line.append(format(cells.get(i)));
        }
        line.append("\r\n");
        out.write(line.toString());
    }

    static String format(Cell cell) {
        if (cell == null) {
            return "";
        }
        String value = switch (cell) {
            case Text text -> TableWriter.neutralise(text.value());
            case Number number -> number.value().toString();
            case Time time -> time.value().toString();
        };
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    @Override
    public void close() throws IOException {
        out.flush();
    }
}
