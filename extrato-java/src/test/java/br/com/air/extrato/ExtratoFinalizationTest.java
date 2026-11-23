package br.com.air.extrato;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExtratoFinalizationTest {
    private static final String SECRET = "secret-password-123";
    @TempDir Path dir;
    private final List<String> events = new ArrayList<>();
    private final Path output = Path.of("output");
    private final Path log = Path.of("log");
    private int reportWriteFailure;
    private boolean reportCloseFailure;
    private int logWriteFailure;
    private boolean logCloseFailure;
    private boolean logOpenFailure;
    private boolean sourceCloseFailure;
    private int reportWrites;
    private int logWrites;

    private Extrato.AbrirSaida streams() {
        return path -> {
            boolean report = path.equals(output);
            if (!report && logOpenFailure) throw new IOException(SECRET);
            OutputStream file = Files.newOutputStream(dir.resolve(path));
            return new FilterOutputStream(file) {
                @Override public void write(byte[] bytes) throws IOException {
                    int number = report ? ++reportWrites : ++logWrites;
                    events.add((report ? "report-write-" : "log-write-") + number);
                    if (number == (report ? reportWriteFailure : logWriteFailure)) throw new IOException(SECRET);
                    out.write(bytes);
                }
                @Override public void close() throws IOException {
                    events.add(report ? "report-close" : "log-close");
                    out.close();
                    if (report ? reportCloseFailure : logCloseFailure) throw new IOException(SECRET);
                }
            };
        };
    }

    private Fontes.Leitura source() {
        Operacao item = new Operacao("0042123456", "FIRST", "CPE", "Credit", new BigDecimal("12.34"),
                12, 3, new BigDecimal("9.01"), "20261231", "A");
        return new Fontes.Leitura() {
            boolean read;
            public Operacao proxima() { if (read) return null; read = true; return item; }
            public void close() throws IOException {
                events.add("source-close");
                if (sourceCloseFailure) throw new IOException(SECRET);
            }
        };
    }

    private int run() throws IOException {
        return Extrato.executar(this::source, output, log, LocalTime.NOON, StandardCharsets.ISO_8859_1,
                Formato::detalhe, streams());
    }

    private String logText() throws IOException { return Files.readString(dir.resolve(log), StandardCharsets.ISO_8859_1); }
    private String record(String value) { return value + " ".repeat(132 - value.length()); }
    private String start() { return record("120000  PGMEXTRT INFO CREDIT STATEMENT STARTED"); }
    private String completed() { return record("120000  PGMEXTRT INFO CREDIT STATEMENT COMPLETED"); }
    private String error() { return record("120000  PGMEXTRT ERROR BATCH PROCESSING FAILED: input or output error"); }

    @Test void successfulBytesAndCloseOrderAreUnchanged() throws Exception {
        assertEquals(0, run());
        String header = " " + "BANCO AIR S.A." + " ".repeat(16) + "CREDIT OPERATIONS STATEMENT";
        String detail = "FIRST         " + "  " + "Credit              " + "  " + "          12.34" + "  "
                + "    12" + "  " + "     3" + "  " + "           9.01" + "  " + "31/12/2026" + "  " + "ACTIVE     ";
        String footer = "TOTAL OPERATIONS:" + " ".repeat(5) + "      1" + " ".repeat(20)
                + "TOTAL BALANCE:" + " ".repeat(10) + "           9.01";
        assertArrayEquals((record(header) + record(detail) + record(footer)).getBytes(StandardCharsets.ISO_8859_1),
                Files.readAllBytes(dir.resolve(output)));
        assertArrayEquals((start() + completed()).getBytes(StandardCharsets.ISO_8859_1), Files.readAllBytes(dir.resolve(log)));
        assertEquals(List.of("log-write-1", "report-write-1", "report-write-2", "report-write-3",
                "source-close", "report-close", "log-write-2", "log-close"), events);
    }

    @Test void reportCloseFailureIsSanitizedAndNeverLogsCompletion() throws Exception {
        reportCloseFailure = true;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        try (PrintStream stderr = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            System.setErr(stderr);
            assertEquals(12, run());
        } finally { System.setErr(previous); }
        assertEquals(start() + error(), logText());
        assertFalse(logText().contains("COMPLETED"));
        assertFalse(logText().contains(SECRET));
        assertEquals("Batch processing failed: input or output error" + System.lineSeparator(), captured.toString(StandardCharsets.UTF_8));
        assertFalse(captured.toString(StandardCharsets.UTF_8).contains(SECRET));
        assertTrue(events.indexOf("source-close") < events.indexOf("report-close"));
        assertTrue(events.indexOf("report-close") < events.indexOf("log-write-2"));
        assertTrue(events.indexOf("log-write-2") < events.indexOf("log-close"));
        assertEquals(396, Files.size(dir.resolve(output)));
    }

    @Test void eachReportWriteFailureClosesSourceAndReportAndLogsError() throws Exception {
        for (int failure = 1; failure <= 3; failure++) {
            events.clear(); reportWrites = 0; logWrites = 0; reportWriteFailure = failure;
            assertEquals(12, run());
            assertEquals(start() + error(), logText());
            assertTrue(events.indexOf("source-close") < events.indexOf("report-close"));
            assertTrue(events.indexOf("report-close") < events.indexOf("log-write-2"));
            assertEquals("log-close", events.getLast());
        }
    }

    @Test void sourceCloseFailureAndSuppressedReportCloseFailureAreSanitized() throws Exception {
        sourceCloseFailure = true;
        reportCloseFailure = true;
        assertEquals(12, run());
        assertEquals(start() + error(), logText());
        assertTrue(events.indexOf("source-close") < events.indexOf("report-close"));
        assertEquals("log-close", events.getLast());
    }

    @Test void logOpenAndStartFailuresPropagateAndCloseReport() throws Exception {
        logOpenFailure = true;
        assertThrows(IOException.class, this::run);
        assertEquals(List.of("report-close"), events);
        logOpenFailure = false; logWriteFailure = 1; events.clear();
        assertThrows(IOException.class, this::run);
        assertEquals(List.of("log-write-1", "report-close", "log-close"), events);
        assertEquals("", logText());
    }

    @Test void completionWriteAndLogCloseFailuresStillPropagate() throws Exception {
        logWriteFailure = 2;
        assertThrows(IOException.class, this::run);
        assertEquals(start(), logText());
        assertTrue(events.indexOf("report-close") < events.indexOf("log-write-2"));
        assertEquals("log-close", events.getLast());
        events.clear(); reportWrites = 0; logWrites = 0; logWriteFailure = 0; logCloseFailure = true;
        assertThrows(IOException.class, this::run);
        assertEquals(start() + completed(), logText());
        assertEquals("log-close", events.getLast());
    }

    @Test void errorLogWriteFailureIsIgnoredButLogCloseFailureStillPropagates() throws Exception {
        reportCloseFailure = true; logWriteFailure = 2;
        assertEquals(12, run());
        assertEquals(start(), logText());
        assertEquals("log-close", events.getLast());
        events.clear(); reportWrites = 0; logWrites = 0; logCloseFailure = true;
        assertThrows(IOException.class, this::run);
        assertEquals(start(), logText());
        assertEquals("log-close", events.getLast());
    }
}
