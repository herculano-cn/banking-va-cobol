package br.com.air.extrato;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegressaoTest {
    @TempDir Path dir;
    private Operacao operation() { return new Operacao("a", "FIRST", "CPE", "x", BigDecimal.ONE, 1, 0, BigDecimal.ONE, "20260101", "A"); }
    @Test void sourceOpenFailureIsFatalAndLogged() throws Exception {
        Path output = dir.resolve("output"), log = dir.resolve("log");
        int rc = Extrato.executar(() -> { throw new IOException("sensitive input"); }, output, log, LocalTime.NOON, StandardCharsets.ISO_8859_1, Formato::detalhe);
        assertEquals(12, rc);
        assertEquals(0, Files.size(output));
        assertEquals(264, Files.size(log));
        assertFalse(Files.readString(log).contains("sensitive input"));
    }
    @Test void fetchFailureDoesNotPretendToSucceed() throws Exception {
        Path output = dir.resolve("output"), log = dir.resolve("log");
        int rc = Extrato.executar(() -> new Fontes.Leitura() {
            int n;
            public Operacao proxima() throws IOException { if (n++ == 0) return operation(); throw new IOException("fetch failed"); }
            public void close() { }
        }, output, log, LocalTime.NOON, StandardCharsets.ISO_8859_1, Formato::detalhe);
        assertEquals(12, rc);
        assertEquals(264, Files.size(output));
        assertTrue(Files.readString(output).contains("FIRST"));
    }
    @Test void formatFailureIsFatalRatherThanBlankRecord() throws Exception {
        Path output = dir.resolve("output"), log = dir.resolve("log");
        int rc = Extrato.executar(() -> Fontes.lista(List.of(operation())), output, log, LocalTime.NOON,
                StandardCharsets.ISO_8859_1, op -> { throw new IllegalArgumentException("invalid"); });
        assertEquals(12, rc);
        assertEquals(132, Files.size(output));
    }
    @Test void hardlinksAndDanglingLinksAreRejected() throws Exception {
        Path input = dir.resolve("input"), alias = dir.resolve("alias"), log = dir.resolve("log");
        Files.writeString(input, "original");
        Files.createLink(alias, input);
        assertEquals(12, new Main().run("--mock", input.toString(), "--output", alias.toString(), "--log", log.toString()));
        assertEquals("original", Files.readString(input));
        Path dangling = dir.resolve("dangling");
        Files.createSymbolicLink(dangling, dir.resolve("missing"));
        assertEquals(12, new Main().run("--mock", input.toString(), "--output", dangling.toString(), "--log", log.toString()));
        assertFalse(Files.exists(dir.resolve("missing")));
    }
}
