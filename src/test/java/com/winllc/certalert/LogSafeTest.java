package com.winllc.certalert;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class LogSafeTest {

    @Test
    void everyLineBreakAndControlCharacterIsKeptOffTheLine() {
        assertThat(LogSafe.oneLine("a\r\nb\nc\rd\u0085e f g\th")).isEqualTo("a b c d e f g h");
        assertThat(LogSafe.oneLine("red\u001b[31m")).isEqualTo("red?[31m");
        assertThat(LogSafe.oneLine(List.of("uid=a\nforged", "b"))).isEqualTo("[uid=a forged, b]");
        assertThat(LogSafe.oneLine(null)).isNull();
        assertThat(LogSafe.oneLine("CN=Plain, O=Example")).isEqualTo("CN=Plain, O=Example");
    }
}
