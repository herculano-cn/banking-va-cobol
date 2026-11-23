package br.com.air.extrato;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@QuarkusMain
public class Main implements QuarkusApplication {
    public static void main(String... args) { Quarkus.run(Main.class, args); }
    @Override public int run(String... args) {
        try {
            Map<String, String> opts = argumentos(args);
            if (opts.containsKey("help")) {
                System.out.println("Usage: java -jar quarkus-run.jar [--source mock|db2] [--mock PATH] [--date YYYYMMDD] [--branch NNNN] [--account ACCOUNT] [--output PATH] [--log PATH] [--encoding CHARSET]");
                return 0;
            }
            String source = opts.getOrDefault("source", "mock");
            LocalDate date = Formato.date(opts.getOrDefault("date", "20260508"));
            String branch = opts.getOrDefault("branch", "0042");
            String account = opts.getOrDefault("account", "0042123456");
            if (branch.isBlank() || branch.length() > 4 || !branch.equals(branch.strip()) || account.isBlank() || account.length() > 10 || !account.equals(account.strip()))
                throw new IllegalArgumentException("Branch must be 1-4 characters and account must be 1-10 characters without surrounding spaces");
            Path mock = Path.of(opts.getOrDefault("mock", "../mock-operacoes.dat"));
            Path output = Path.of(opts.getOrDefault("output", "SAIDA"));
            Path log = Path.of(opts.getOrDefault("log", "LOGFILE"));
            Charset charset = Charset.forName(opts.getOrDefault("encoding", "ISO-8859-1"));
            Extrato.validarCaminhos(output, log, source.equals("mock") ? mock : null);
            Extrato.Abrir open = switch (source) {
                case "mock" -> () -> Fontes.abrirMock(mock, account, branch, date);
                case "db2" -> () -> Fontes.db2(System.getenv("EXTRATO_DB2_URL"), System.getenv("EXTRATO_DB2_USER"),
                        System.getenv("EXTRATO_DB2_PASSWORD"), account, branch, date);
                default -> throw new IllegalArgumentException("Source must be mock or db2");
            };
            return Extrato.executar(open, output, log, LocalTime.now(), charset, Formato::detalhe);
        } catch (Exception e) {
            String reason = "invalid arguments, configuration or file access";
            if (e instanceof java.time.format.DateTimeParseException) reason = "date must be a valid YYYYMMDD date";
            else if (e instanceof java.nio.charset.UnsupportedCharsetException || e instanceof java.nio.charset.IllegalCharsetNameException) reason = "unsupported output encoding";
            else if (e instanceof IllegalArgumentException && e.getMessage() != null
                    && Set.of("Invalid or missing option", "Repeated option", "Source must be mock or db2",
                    "Branch must be 1-4 characters and account must be 1-10 characters without surrounding spaces", "Date year must be at least 0001",
                    "Input, output and log must be distinct files").contains(e.getMessage())) reason = e.getMessage();
            System.err.println("Batch failed: " + reason);
            return 12;
        }
    }
    static Map<String, String> argumentos(String[] args) {
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String key = args[i];
            if (key.equals("--help")) { result.put("help", ""); continue; }
            if (!Set.of("--source", "--mock", "--date", "--branch", "--account", "--output", "--log", "--encoding").contains(key)
                    || i + 1 >= args.length || args[i + 1].startsWith("--"))
                throw new IllegalArgumentException("Invalid or missing option");
            if (result.put(key.substring(2), args[++i]) != null)
                throw new IllegalArgumentException("Repeated option");
        }
        return result;
    }
}
