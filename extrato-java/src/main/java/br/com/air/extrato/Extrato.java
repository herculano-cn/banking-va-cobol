package br.com.air.extrato;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;

final class Extrato {
    private Extrato() {}
    @FunctionalInterface interface Abrir { Fontes.Leitura abrir() throws Exception; }
    @FunctionalInterface interface AbrirSaida { OutputStream abrir(Path path) throws IOException; }

    static void validarCaminhos(Path output, Path log, Path input) throws IOException {
        verificarLinks(output);
        verificarLinks(log);
        if (input != null) verificarLinks(input);
        distintos(output, log);
        if (input != null) { distintos(output, input); distintos(log, input); }
    }
    private static void verificarLinks(Path path) throws IOException {
        Path absolute = path.toAbsolutePath();
        Path current = absolute.getRoot();
        int index = 0;
        for (Path part : absolute) {
            current = current.resolve(part);
            index++;
            try {
                var attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink()) current.toRealPath();
            } catch (NoSuchFileException e) {
                if (index != absolute.getNameCount()) throw e;
                if (Files.isSymbolicLink(current)) throw new IOException("Dangling symbolic link", e);
            }
        }
    }
    private static void distintos(Path a, Path b) throws IOException {
        if (a.toFile().getCanonicalFile().equals(b.toFile().getCanonicalFile())
                || (Files.exists(a) && Files.exists(b) && Files.isSameFile(a, b)))
            throw new IllegalArgumentException("Input, output and log must be distinct files");
    }
    static int executar(Abrir open, Path output, Path log, LocalTime time, Charset charset,
                        Function<Operacao, String> formatter) throws IOException {
        return executar(open, output, log, time, charset, formatter, Files::newOutputStream);
    }
    static int executar(Abrir open, Path output, Path log, LocalTime time, Charset charset,
                        Function<Operacao, String> formatter, AbrirSaida openOutput) throws IOException {
        validarCaminhos(output, log, null);
        OutputStream out = openOutput.abrir(output);
        OutputStream logStream;
        try {
            logStream = openOutput.abrir(log);
        } catch (IOException | RuntimeException | Error e) {
            try { out.close(); } catch (Throwable closeFailure) { e.addSuppressed(closeFailure); }
            throw e;
        }
        try (OutputStream logOut = logStream) {
            String prefix = null;
            boolean started = false;
            try (out) {
                prefix = time.format(DateTimeFormatter.ofPattern("HHmmss")) + "  PGMEXTRT ";
                log(logOut, charset, prefix, "INFO", "CREDIT STATEMENT STARTED");
                started = true;
                try (Fontes.Leitura source = open.abrir()) {
                    gravar(out, Formato.cabecalho(), charset);
                    BigDecimal total = BigDecimal.ZERO;
                    long count = 0;
                    Operacao op;
                    while ((op = source.proxima()) != null) {
                        String line = formatter.apply(op);
                        gravar(out, line, charset);
                        total = total.add(op.saldo());
                        count = Math.addExact(count, 1);
                    }
                    gravar(out, Formato.rodape(count, total), charset);
                }
            } catch (Exception e) {
                if (!started) {
                    if (e instanceof IOException io) throw io;
                    throw (RuntimeException) e;
                }
                try { log(logOut, charset, prefix, "ERROR", "BATCH PROCESSING FAILED: " + safeMessage(e)); }
                catch (IOException | RuntimeException ignored) { }
                System.err.println("Batch processing failed: " + safeMessage(e));
                return 12;
            }
            log(logOut, charset, prefix, "INFO", "CREDIT STATEMENT COMPLETED");
            return 0;
        }
    }
    private static String safeMessage(Exception e) {
        if (e instanceof java.sql.SQLException) return "DB2 operation failed";
        if (e instanceof java.nio.charset.CharacterCodingException) return "unmappable record in output encoding";
        if (e instanceof java.nio.file.NoSuchFileException) return "input file or parent directory not found";
        if (e instanceof IOException) return "input or output error";
        if (e instanceof IllegalArgumentException && e.getMessage() != null
                && e.getMessage().matches("Invalid mock (field count|account|branch|unresolvable branch \\(account shorter than four characters\\)|status|modality|reference date|due date|original amount|balance|installments|paid installments) at line [0-9]+")) return e.getMessage();
        if (e instanceof IllegalArgumentException && e.getMessage() != null
                && java.util.Set.of("Numeric field overflow", "Invalid or oversized fixed-width field", "Record encoding must produce exactly 132 bytes", "Missing amount", "Unsupported operation status").contains(e.getMessage())) return e.getMessage();
        if (e instanceof ArithmeticException) return "numeric precision or count overflow";
        return "invalid data or configuration";
    }
    private static void log(OutputStream stream, Charset charset, String prefix, String level, String message) throws IOException {
        gravar(stream, Formato.fixo(prefix + level + " " + message, 132), charset);
    }
    private static void gravar(OutputStream stream, String line, Charset charset) throws IOException {
        if (line.length() != 132) throw new IllegalArgumentException("Record must contain 132 characters");
        var encoder = charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        var bytes = encoder.encode(CharBuffer.wrap(line));
        if (bytes.remaining() != 132) throw new IllegalArgumentException("Record encoding must produce exactly 132 bytes");
        byte[] data = new byte[132];
        bytes.get(data);
        stream.write(data);
    }
}
