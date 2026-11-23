package br.com.air.extrato;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BatchCliIT {
    @TempDir Path dir;

    // Literal field widths and padding are independent of the production formatter.
    private static final String HEADER = " BANCO AIR S.A.                CREDIT OPERATIONS STATEMENT"
            + "                                                  " + "                        ";
    private static final String FIRST = "A-FIRST       " + "  " + "CAFÉ CREDIT         " + "  "
            + "           1.25" + "  " + "     2" + "  " + "     1" + "  "
            + "           0.99" + "  " + "01/02/2026" + "  " + "DELINQUENT " + "                     ";
    private static final String SECOND = "B-SECOND      " + "  " + "PERSONAL LOAN       " + "  "
            + "          12.34" + "  " + "    12" + "  " + "     3" + "  "
            + "           9.01" + "  " + "31/12/2026" + "  " + "ACTIVE     " + "                     ";
    private static final String THIRD = "0-INPUT-FIRST " + "  " + "PAYROLL LOAN        " + "  "
            + "         200.10" + "  " + "    10" + "  " + "     5" + "  "
            + "         100.50" + "  " + "29/02/2028" + "  " + "ACTIVE     " + "                     ";
    private static final String FOOTER = "TOTAL OPERATIONS:     " + "      3" + "                    "
            + "TOTAL BALANCE:          " + "         110.50" + "                                            ";
    private static final String EMPTY_FOOTER = "TOTAL OPERATIONS:     " + "      0" + "                    "
            + "TOTAL BALANCE:          " + "           0.00" + "                                            ";
    private static final String INPUT =
            "0042123456|0-INPUT-FIRST|CSG|PAYROLL LOAN|200.10|10|5|100.50|20280229|A|0042|20260508\n"
            + "0042123456|B-SECOND|CPE|PERSONAL LOAN|12.34|12|3|9.01|20261231|A|0042|20260507\n"
            + "0042123456|A-FIRST|CPE|CAFÉ CREDIT|1.25|2|1|0.99|20260201|I|0042|20260508\n"
            + "9999123456|ACCOUNT-OUT|CPE|OTHER ACCOUNT|1|1|0|1|20260101|A|0042|20260508\n"
            + "0042123456|BRANCH-OUT|CPE|OTHER BRANCH|1|1|0|1|20260101|A|0043|20260508\n"
            + "0042123456|STATUS-OUT|CPE|CLOSED|1|1|0|1|20260101|Q|0042|20260508\n"
            + "0042123456|FUTURE-OUT|CPE|FUTURE|1|1|0|1|20260101|A|0042|20260509\n";

    private int run(String... args) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-jar");
        command.add(Path.of("target/quarkus-app/quarkus-run.jar").toAbsolutePath().toString());
        command.addAll(java.util.List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(dir.resolve("console").toFile()).start();
        if (!process.waitFor(45, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("CLI timed out"); }
        return process.exitValue();
    }

    private String[] args(Path input, Path output, Path log, String charset) {
        return new String[] {"--source", "mock", "--mock", input.toString(), "--account", "0042123456",
                "--branch", "0042", "--date", "20260508", "--output", output.toString(), "--log", log.toString(),
                "--encoding", charset};
    }

    private void assertRecords(Path output, Charset charset, String... records) throws Exception {
        for (String record : records) assertEquals(132, record.length(), "Expected literal must be 132 characters");
        String expected = String.join("", records);
        byte[] expectedBytes = expected.getBytes(charset);
        assertEquals(records.length * 132, expectedBytes.length, "Expected records must be 132 bytes each");
        assertEquals(expected, new String(expectedBytes, charset), "Expected records must round-trip");
        assertArrayEquals(expectedBytes, Files.readAllBytes(output), "Complete fixed-record output differs");
    }

    private void assertLog(Path log, Charset charset, String finalLevelAndMessage) throws Exception {
        byte[] bytes = Files.readAllBytes(log);
        assertEquals(264, bytes.length, "Expected exactly two 132-byte log records");
        String records = new String(bytes, charset);
        assertArrayEquals(bytes, records.getBytes(charset), "Log must round-trip through the selected charset");
        assertLogRecord(records.substring(0, 132), "INFO CREDIT STATEMENT STARTED");
        assertLogRecord(records.substring(132), finalLevelAndMessage);
    }

    private void assertLogRecord(String record, String levelAndMessage) {
        assertTrue(record.substring(0, 6).matches("(?:[01][0-9]|2[0-3])[0-5][0-9][0-5][0-9]"), "Log must begin with a valid HHmmss time");
        String expected = "  PGMEXTRT " + levelAndMessage;
        assertEquals(expected + " ".repeat(126 - expected.length()), record.substring(6), "Fixed-record log differs");
    }

    @Test void correctedGoldenIncludesAllSelectedRowsAndSortsThem() throws Exception {
        Path input = dir.resolve("input"), output = dir.resolve("output"), log = dir.resolve("log");
        Files.writeString(input, INPUT, StandardCharsets.ISO_8859_1);
        assertEquals(0, run(args(input, output, log, "ISO-8859-1")));
        assertRecords(output, StandardCharsets.ISO_8859_1, HEADER, FIRST, SECOND, THIRD, FOOTER);
        assertLog(log, StandardCharsets.ISO_8859_1, "INFO CREDIT STATEMENT COMPLETED");
    }

    @Test void ibm037GoldenUsesTheSameIndependentRecords() throws Exception {
        Path input = dir.resolve("input"), output = dir.resolve("output"), log = dir.resolve("log");
        Files.writeString(input, INPUT, StandardCharsets.ISO_8859_1);
        Charset charset = Charset.forName("IBM037");
        assertEquals(0, run(args(input, output, log, "IBM037")));
        assertRecords(output, charset, HEADER, FIRST, SECOND, THIRD, FOOTER);
        assertLog(log, charset, "INFO CREDIT STATEMENT COMPLETED");
    }

    @Test void noMatchProducesHeaderAndZeroFooter() throws Exception {
        Path input = dir.resolve("input"), output = dir.resolve("output"), log = dir.resolve("log");
        Files.writeString(input, INPUT, StandardCharsets.ISO_8859_1);
        String[] args = args(input, output, log, "ISO-8859-1");
        args[5] = "OTHER"; // Account, distinct from every input row.
        assertEquals(0, run(args));
        assertRecords(output, StandardCharsets.ISO_8859_1, HEADER, EMPTY_FOOTER);
        assertLog(log, StandardCharsets.ISO_8859_1, "INFO CREDIT STATEMENT COMPLETED");
    }

    @Test void malformedInputFailsBeforeWritingReportAndDoesNotExposeInput() throws Exception {
        Path input = dir.resolve("input"), output = dir.resolve("output"), log = dir.resolve("log");
        Files.writeString(input, "sensitive malformed input\n", StandardCharsets.ISO_8859_1);
        assertEquals(12, run(args(input, output, log, "ISO-8859-1")));
        assertEquals(0, Files.size(output));
        assertLog(log, StandardCharsets.ISO_8859_1, "ERROR BATCH PROCESSING FAILED: Invalid mock field count at line 1");
        String console = Files.readString(dir.resolve("console"));
        assertTrue(console.contains("Batch processing failed: Invalid mock field count at line 1"));
        assertFalse(console.contains("sensitive malformed input"));
        assertEquals(12, run("--mock", input.toString(), "--output", input.toString(), "--log", log.toString()));
        assertEquals("sensitive malformed input\n", Files.readString(input));
    }

    @Test void formattingFailureLeavesPartialReportAndFatalLog() throws Exception {
        Path input = dir.resolve("input"), output = dir.resolve("output"), log = dir.resolve("log");
        Files.writeString(input, "0042123456|A-FIRST|CPE|CAFÉ CREDIT|1.25|2|1|0.99|20260201|I|0042|20260508\n"
                + "0042123456|B-FAIL|CPE|DESCRIPTION IS TOO LONG|1|1|0|1|20260101|A|0042|20260508\n", StandardCharsets.ISO_8859_1);
        assertEquals(12, run(args(input, output, log, "ISO-8859-1")));
        assertRecords(output, StandardCharsets.ISO_8859_1, HEADER, FIRST);
        assertLog(log, StandardCharsets.ISO_8859_1, "ERROR BATCH PROCESSING FAILED: Invalid or oversized fixed-width field");
        assertTrue(Files.readString(dir.resolve("console")).contains("Batch processing failed: Invalid or oversized fixed-width field"));
    }
}
