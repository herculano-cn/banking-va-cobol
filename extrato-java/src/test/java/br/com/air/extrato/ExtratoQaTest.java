package br.com.air.extrato;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExtratoQaTest {
    @TempDir Path dir;
    @Test void outputCannotOverwriteInput() throws Exception {
        Path input = dir.resolve("mock");
        String text = "0042123456|A|CPE|x|1|1|0|1|20260101|A\n";
        Files.writeString(input, text);
        assertEquals(12, new Main().run("--mock", input.toString(), "--output", input.toString(), "--log", dir.resolve("log").toString()));
        assertEquals(text, Files.readString(input));
    }
    @Test void outputAndLogCannotAlias() throws Exception {
        Path output = dir.resolve("output"), log = dir.resolve("log"), input = dir.resolve("mock");
        Files.writeString(output, "previous");
        Files.createSymbolicLink(log, output);
        Files.writeString(input, "");
        assertEquals(12, new Main().run("--mock", input.toString(), "--output", output.toString(), "--log", log.toString()));
        assertEquals("previous", Files.readString(output));
    }
    @Test void invalidOptionsAndDateFail() {
        assertEquals(12, new Main().run("--date", "20260230"));
        assertEquals(12, new Main().run("--unknown", "value"));
        assertEquals(12, new Main().run("--date", "20260508", "--date", "20260508"));
    }
}
