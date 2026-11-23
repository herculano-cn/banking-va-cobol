package br.com.air.extrato;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Db2IT {
    @TempDir Path dir;

    @Test void consultaEExtratoComDadosExternos() {
        runSafely(this::qualificar);
    }

    @FunctionalInterface interface Qualification { void run() throws Throwable; }

    static void runSafely(Qualification qualification) {
        try {
            qualification.run();
        } catch (Throwable failure) {
            // Replace even our own failures: resource cleanup may have attached sensitive suppressed exceptions.
            throw new AssertionError(failure instanceof SafeFailure ? failure.getMessage()
                    : "DB2 qualification failed (details omitted)");
        }
    }

    private static final class SafeFailure extends AssertionError {
        SafeFailure(String message) { super(message); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new SafeFailure(message);
    }

    private void qualificar() throws Exception {
        String url = obrigatoria("EXTRATO_DB2_URL");
        String usuario = obrigatoria("EXTRATO_DB2_USER");
        String senha = obrigatoria("EXTRATO_DB2_PASSWORD");
        String data = obrigatoria("EXTRATO_DB2_IT_ACCOUNT");
        String alternativa = obrigatoria("EXTRATO_DB2_IT_OTHER_ACCOUNT");
        String agencia = obrigatoria("EXTRATO_DB2_IT_BRANCH");
        String corteTexto = obrigatoria("EXTRATO_DB2_IT_CORTE");
        Path operacoes = fixturePath(obrigatoria("EXTRATO_DB2_IT_OPERACOES"));
        Path golden = fixturePath(obrigatoria("EXTRATO_DB2_IT_SAIDA"));
        require(!data.equals(alternativa) && !agencia.isBlank() && agencia.length() <= 4 && agencia.equals(agencia.strip()), "Distinct accounts and a 1-4 character branch are required");
        LocalDate corte = cutoff(corteTexto);
        require(Files.isRegularFile(operacoes) && Files.isReadable(operacoes), "Operations fixture must be readable");
        require(Files.isRegularFile(golden) && Files.isReadable(golden), "Expected output must be readable");
        List<Operacao> esperado = lerEsperado(operacoes);
        require(esperado.size() >= 3, "Fixture must contain at least three selected operations");
        require(esperado.stream().allMatch(o -> o.conta().equals(data) && List.of("A", "I").contains(o.status())),
                "Fixture must contain only the selected account and statuses");
        require(esperado.stream().map(Operacao::modalidade).distinct().count() >= 2,
                "Fixture must contain distinct modalities");
        require(esperado.stream().anyMatch(a -> esperado.stream().anyMatch(b -> a != b
                && a.modalidade().equals(b.modalidade()) && !a.contrato().equals(b.contrato()))),
                "Fixture must contain distinct contracts within one modality");

        // Each witness satisfies the other filters, isolating its exclusion condition.
        try (Connection c = DriverManager.getConnection(url, usuario, senha)) {
            testemunha(c, data, agencia, corte, "OC.CD_AGENCIA <> ? AND OC.CD_STATUS IN ('A','I') AND OC.DT_REFERENCIA <= ?", "different branch");
            testemunha(c, data, agencia, corte, "OC.CD_AGENCIA = ? AND OC.CD_STATUS NOT IN ('A','I') AND OC.DT_REFERENCIA <= ?", "excluded status");
            testemunha(c, data, agencia, corte, "OC.CD_AGENCIA = ? AND OC.CD_STATUS IN ('A','I') AND OC.DT_REFERENCIA > ?", "later reference date");
            testemunha(c, alternativa, agencia, corte, "OC.CD_AGENCIA = ? AND OC.CD_STATUS IN ('A','I') AND OC.DT_REFERENCIA <= ?", "different account");
        } catch (java.sql.SQLException e) {
            throw new SafeFailure("DB2 witness query failed; check connectivity, privileges and schema (details omitted)");
        }

        List<Operacao> obtido = new ArrayList<>();
        try (Fontes.Leitura leitura = Fontes.db2(url, usuario, senha, data, agencia, corte)) {
            Operacao op;
            while ((op = leitura.proxima()) != null) obtido.add(normalizar(op));
        } catch (Exception e) {
            throw new SafeFailure("DB2 read failed; check connectivity, privileges, schema and data (details omitted)");
        }
        compareOperations(esperado, obtido);

        Path saida = dir.resolve("SAIDA"), log = dir.resolve("LOGFILE");
        int rc;
        try {
            rc = Extrato.executar(() -> abrirSemDetalhes(url, usuario, senha, data, agencia, corte), saida, log,
                    LocalTime.NOON, StandardCharsets.ISO_8859_1, Formato::detalhe);
        } catch (IOException | RuntimeException e) {
            throw new SafeFailure("DB2 processing or output I/O failed (details omitted)");
        }
        require(rc == 0, "DB2 processing failed");
        compareBytes(golden, saida);
    }

    private static String obrigatoria(String nome) {
        String valor = System.getenv(nome);
        require(valor != null && !valor.isBlank(), "Configure environment variable " + nome + " for db2-it");
        return valor;
    }

    private static Fontes.Leitura abrirSemDetalhes(String url, String usuario, String senha, String data, String agencia, LocalDate corte) throws SQLException {
        try {
            Fontes.Leitura leitura = Fontes.db2(url, usuario, senha, data, agencia, corte);
            return new Fontes.Leitura() {
                public Operacao proxima() throws SQLException {
                    try { return leitura.proxima(); }
                    catch (Exception e) { throw new SQLException("DB2 read failed"); }
                }
                public void close() throws SQLException {
                    try { leitura.close(); }
                    catch (Exception e) { throw new SQLException("DB2 close failed"); }
                }
            };
        } catch (Exception e) {
            throw new SQLException("DB2 open failed");
        }
    }

    private static void testemunha(Connection c, String data, String agencia, LocalDate corte, String filtro, String descricao) throws Exception {
        String sql = "SELECT COUNT(*) FROM DB2PROD.TB_OPERACAO_CREDITO OC INNER JOIN DB2PROD.TB_CLIENTE CLI "
                + "ON CLI.NR_CONTA = OC.NR_CONTA WHERE OC.NR_CONTA = ? AND " + filtro + " WITH UR";
        try (PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, data);
            p.setString(2, agencia);
            p.setObject(3, corte);
            try (var r = p.executeQuery()) {
                require(r.next() && r.getLong(1) > 0, "External data must include witness: " + descricao);
            }
        }
    }

    static Path fixturePath(String value) {
        try { return Path.of(value); }
        catch (RuntimeException e) { throw new SafeFailure("Invalid fixture path (details omitted)"); }
    }

    static LocalDate cutoff(String value) {
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException e) { throw new SafeFailure("Invalid cutoff date (details omitted)"); }
    }

    static List<Operacao> lerEsperado(Path arquivo) {
        List<Operacao> ops = new ArrayList<>();
        List<String> lines;
        try { lines = Files.readAllLines(arquivo, StandardCharsets.UTF_8); }
        catch (IOException | SecurityException e) { throw new SafeFailure("Operations fixture read failed (details omitted)"); }
        for (int i = 0; i < lines.size(); i++) {
            String linha = lines.get(i);
            if (linha.isBlank() || linha.startsWith("#")) continue;
            String[] f = linha.split("\t", -1);
            require(f.length == 10, "Invalid fixture field count at line " + (i + 1));
            try {
                ops.add(normalizar(new Operacao(f[0], f[1], f[2], f[3], new BigDecimal(f[4]),
                        Integer.parseInt(f[5]), Integer.parseInt(f[6]), new BigDecimal(f[7]), f[8], f[9])));
            } catch (NumberFormatException e) {
                throw new SafeFailure("Invalid numeric fixture field at line " + (i + 1));
            }
        }
        return ops;
    }

    static void compareOperations(List<Operacao> expected, List<Operacao> actual) {
        require(expected.size() == actual.size(), "Operation row count differs from independent fixture");
        for (int i = 0; i < expected.size(); i++) {
            require(Objects.equals(expected.get(i), actual.get(i)),
                    "Operation differs from independent fixture at row " + (i + 1));
        }
    }

    static void compareBytes(Path expected, Path actual) {
        byte[] expectedBytes, actualBytes;
        try {
            expectedBytes = Files.readAllBytes(expected);
            actualBytes = Files.readAllBytes(actual);
        } catch (IOException | SecurityException e) {
            throw new SafeFailure("Expected or actual output read failed (details omitted)");
        }
        require(Arrays.equals(expectedBytes, actualBytes), "Output differs from independent expected bytes");
    }

    private static Operacao normalizar(Operacao op) {
        return new Operacao(op.conta().stripTrailing(), op.contrato().stripTrailing(), op.modalidade().stripTrailing(),
                op.descricao().stripTrailing(), op.original().stripTrailingZeros(), op.parcelas(), op.pagas(),
                op.saldo().stripTrailingZeros(), op.vencimento(), op.status().stripTrailing());
    }
}
