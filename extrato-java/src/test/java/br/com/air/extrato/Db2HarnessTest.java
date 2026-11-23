package br.com.air.extrato;

import static org.junit.jupiter.api.Assertions.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Db2HarnessTest {
    private static final String SECRET = "SENTINEL_PRIVATE_ACCOUNT_CONTRACT_PASSWORD";
    @TempDir Path dir;

    private static AssertionError failsSafely(String category, Db2IT.Qualification action) {
        AssertionError error = assertThrows(AssertionError.class, () -> Db2IT.runSafely(action));
        assertTrue(error.getMessage().contains(category));
        assertNull(error.getCause());
        assertEquals(0, error.getSuppressed().length);
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        assertFalse(trace.toString().contains(SECRET));
        return error;
    }

    private static Operacao operation(String account, String contract, String amount) {
        return new Operacao(account, contract, "CPE", "description", new BigDecimal(amount).stripTrailingZeros(),
                12, 3, new BigDecimal("5.00").stripTrailingZeros(), "20261231", "A");
    }

    @Test void operationComparisonFailsWithoutValuesAndAcceptsExactEquality() {
        Operacao expected = operation(SECRET, SECRET, "12345.67");
        Db2IT.runSafely(() -> Db2IT.compareOperations(List.of(expected), List.of(expected)));
        AssertionError mismatch = failsSafely("row 1", () -> Db2IT.compareOperations(
                List.of(expected), List.of(operation(SECRET, "different", "12345.67"))));
        assertFalse(mismatch.getMessage().contains("12345.67"));
        failsSafely("row count", () -> Db2IT.compareOperations(List.of(expected), List.of()));
        failsSafely("row 1", () -> Db2IT.compareOperations(
                List.of(expected), List.of(operation(SECRET, SECRET, "12345.68"))));
    }

    @Test void malformedNumericFieldsReportPhysicalLineWithoutFixtureContents() throws Exception {
        for (int field : new int[]{4, 5, 6, 7}) {
            String[] fields = {SECRET, SECRET, "CPE", "description", "1.00", "12", "3", "5.00", "20261231", "A"};
            fields[field] = SECRET;
            Path fixture = dir.resolve("numeric-" + field);
            Files.writeString(fixture, "# comment\n\n" + String.join("\t", fields), StandardCharsets.UTF_8);
            failsSafely("numeric fixture field at line 3", () -> Db2IT.lerEsperado(fixture));
        }
        Path invalid = dir.resolve("invalid-fields");
        Files.writeString(invalid, SECRET);
        failsSafely("field count at line 1", () -> Db2IT.lerEsperado(invalid));
        Path valid = dir.resolve("valid");
        Files.writeString(valid, SECRET + "\tcontract\tCPE\tdescription\t1.00\t12\t3\t5.00\t20261231\tA");
        Db2IT.runSafely(() -> Db2IT.compareOperations(List.of(operation(SECRET, "contract", "1")),
                Db2IT.lerEsperado(valid)));
    }

    @Test void inaccessiblePathsAndInvalidConfigurationDoNotLeak() {
        Path missing = dir.resolve(SECRET).resolve("missing");
        failsSafely("Operations fixture read failed", () -> Db2IT.lerEsperado(missing));
        failsSafely("output read failed", () -> Db2IT.compareBytes(missing, missing));
        failsSafely("Invalid fixture path", () -> Db2IT.fixturePath(SECRET + "\0"));
        failsSafely("Invalid cutoff date", () -> Db2IT.cutoff(SECRET));
    }

    @Test void exactGoldenBytesAreComparedWithoutPrintingBytes() throws Exception {
        Path expected = dir.resolve("expected"), actual = dir.resolve("actual");
        Files.writeString(expected, SECRET, StandardCharsets.UTF_8);
        Files.writeString(actual, SECRET, StandardCharsets.UTF_8);
        Db2IT.runSafely(() -> Db2IT.compareBytes(expected, actual));
        Files.writeString(actual, "X" + SECRET.substring(1), StandardCharsets.UTF_8);
        failsSafely("Output differs", () -> Db2IT.compareBytes(expected, actual));
        Files.writeString(actual, SECRET + "different", StandardCharsets.UTF_8);
        failsSafely("Output differs", () -> Db2IT.compareBytes(expected, actual));
        Files.writeString(actual, SECRET.substring(1), StandardCharsets.UTF_8);
        failsSafely("Output differs", () -> Db2IT.compareBytes(expected, actual));
    }

    @Test void exceptionsAndSuppressedDb2FailuresAreDiscarded() {
        failsSafely("qualification failed", () -> {
            SQLException cause = new SQLException(SECRET);
            SQLException failure = new SQLException(SECRET, cause);
            failure.addSuppressed(new SQLException(SECRET));
            throw failure;
        });
        failsSafely("row count", () -> {
            try {
                Db2IT.compareOperations(List.of(operation(SECRET, SECRET, "1")), List.of());
            } catch (AssertionError failure) {
                failure.addSuppressed(new SQLException(SECRET));
                throw failure;
            }
        });
    }
}
