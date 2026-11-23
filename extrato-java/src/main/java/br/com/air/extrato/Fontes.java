package br.com.air.extrato;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class Fontes {
    private Fontes() {}
    interface Leitura extends AutoCloseable {
        Operacao proxima() throws Exception;
        @Override void close() throws Exception;
    }
    static Leitura lista(List<Operacao> ops) {
        var it = ops.iterator();
        return new Leitura() {
            public Operacao proxima() { return it.hasNext() ? it.next() : null; }
            public void close() { }
        };
    }
    static Leitura abrirMock(Path file, String account, String branch, LocalDate cutoff) throws IOException {
        List<Operacao> selected = new ArrayList<>();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] f = line.split("\\|", -1);
                String field = "field count";
                try {
                    if (f.length != 10 && f.length != 12) throw new IllegalArgumentException("Expected 10 or 12 fields");
                    String rowAccount = f[0].stripTrailing();
                    String modality = f[2].stripTrailing();
                    String status = f[9].trim();
                    field = "account";
                    if (rowAccount.isBlank() || rowAccount.length() > 10 || !rowAccount.equals(rowAccount.strip())) throw new IllegalArgumentException();
                    field = "branch";
                    if (f.length == 10 && rowAccount.length() < 4) {
                        field = "unresolvable branch (account shorter than four characters)";
                        throw new IllegalArgumentException();
                    }
                    String rowBranch = f.length == 12 ? f[10].stripTrailing() : rowAccount.substring(0, 4);
                    if (f.length == 12 && (rowBranch.length() > 4 || rowBranch.isBlank() || !rowBranch.equals(rowBranch.strip()))) throw new IllegalArgumentException();
                    field = "status";
                    if (!List.of("A", "I", "Q", "R").contains(status)) throw new IllegalArgumentException();
                    field = "modality";
                    if (!MODALITIES.containsKey(modality)) throw new IllegalArgumentException();
                    field = "reference date";
                    LocalDate reference = f.length == 12 ? Formato.date(f[11].trim()) : cutoff;
                    field = "due date";
                    LocalDate due = Formato.date(f[8].trim());
                    field = "original amount";
                    BigDecimal original = amount(f[4]);
                    field = "balance";
                    BigDecimal balance = amount(f[7]);
                    field = "installments";
                    int installments = quantity(f[5]);
                    field = "paid installments";
                    int paid = quantity(f[6]);
                    Operacao op = new Operacao(rowAccount, f[1], modality, f[3], original, installments, paid,
                            balance, due.format(Formato.DATE), status);
                    if (rowAccount.equals(account) && rowBranch.equals(branch) && (status.equals("A") || status.equals("I"))
                            && !reference.isAfter(cutoff)) {
                        field = "paid installments";
                        if (paid > installments) throw new IllegalArgumentException();
                        selected.add(op);
                    }
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("Invalid mock " + field + " at line " + lineNumber, e);
                }
            }
        }
        selected.sort(Comparator.comparing(Operacao::modalidade).thenComparing(Operacao::contrato));
        return lista(selected);
    }
    private static BigDecimal amount(String text) {
        BigDecimal value = new BigDecimal(text.trim()).setScale(2, RoundingMode.UNNECESSARY);
        if (value.precision() > 13) throw new IllegalArgumentException("Amount outside DECIMAL(13,2)");
        return value;
    }
    private static int quantity(String text) {
        int n = Integer.parseInt(text.trim());
        if (n < Short.MIN_VALUE || n > Short.MAX_VALUE) throw new IllegalArgumentException("Installment count outside SMALLINT");
        return n;
    }
    static final String SQL = "SELECT OC.NR_CONTA, OC.NR_CONTRATO, OC.CD_MODALIDADE, OC.VL_ORIGINAL, "
            + "OC.QT_PARCELAS, OC.QT_PAGAS, OC.VL_SALDO_DEVEDOR, OC.DT_PROX_VENCTO, OC.CD_STATUS "
            + "FROM DB2PROD.TB_OPERACAO_CREDITO OC INNER JOIN DB2PROD.TB_CLIENTE CLI ON CLI.NR_CONTA = OC.NR_CONTA "
            + "WHERE OC.NR_CONTA = ? AND OC.CD_AGENCIA = ? AND OC.CD_STATUS IN ('A', 'I') "
            + "AND OC.DT_REFERENCIA <= ? ORDER BY OC.CD_MODALIDADE, OC.NR_CONTRATO WITH UR";
    private static final Map<String, String> MODALITIES = Map.of(
            "CPE", "PERSONAL CREDIT", "CSG", "PAYROLL LOAN", "CDC", "CONSUMER CREDIT", "RF", "REAL ESTATE FINANCE",
            "RFI", "REAL ESTATE FINANCE", "VEI", "VEHICLE FINANCE", "CHQ", "OVERDRAFT", "ANT", "RECEIVABLE ADVANCE", "OUT", "OTHER");
    static String descricao(String modality) { return MODALITIES.getOrDefault(modality.stripTrailing(), "OTHER"); }
    static Leitura db2(String url, String user, String password, String account, String branch, LocalDate cutoff) throws SQLException {
        if (url == null || url.isBlank() || user == null || user.isBlank() || password == null || password.isBlank())
            throw new IllegalArgumentException("Configure EXTRATO_DB2_URL, EXTRATO_DB2_USER and EXTRATO_DB2_PASSWORD");
        Connection connection = null;
        PreparedStatement statement = null;
        try {
            connection = DriverManager.getConnection(url, user, password);
            statement = connection.prepareStatement(SQL);
            statement.setString(1, account);
            statement.setString(2, branch);
            statement.setObject(3, cutoff);
            ResultSet result = statement.executeQuery();
            Connection c = connection;
            PreparedStatement p = statement;
            return new Leitura() {
                public Operacao proxima() throws SQLException {
                    try {
                        if (!result.next()) return null;
                        String modality = result.getString(3).stripTrailing();
                        LocalDate due = result.getObject(8, LocalDate.class);
                        int installments = result.getInt(5);
                        if (result.wasNull()) throw new SQLException("Missing installment count");
                        int paid = result.getInt(6);
                        if (result.wasNull()) throw new SQLException("Missing paid count");
                        return new Operacao(result.getString(1), result.getString(2), modality, descricao(modality),
                                result.getBigDecimal(4), installments, paid, result.getBigDecimal(7),
                                due.format(Formato.DATE), result.getString(9).stripTrailing());
                    } catch (SQLException | RuntimeException e) { throw new SQLException("DB2 read failed"); }
                }
                public void close() throws SQLException {
                    try (c; p; result) { }
                    catch (SQLException e) { throw new SQLException("DB2 close failed"); }
                }
            };
        } catch (SQLException | RuntimeException e) {
            if (statement != null) try { statement.close(); } catch (SQLException ignored) { }
            if (connection != null) try { connection.close(); } catch (SQLException ignored) { }
            throw new SQLException("DB2 connection or query failed");
        }
    }
}
