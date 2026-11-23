package br.com.air.extrato;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class Db2SourceTest {
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
    @Test void jdbcParametersDatesAmountsAndResources() throws Exception {
        Map<Integer, Object> bound = new HashMap<>();
        boolean[] closed = new boolean[3];
        boolean[] next = {true};
        ResultSet result = proxy(ResultSet.class, (p, m, a) -> switch (m.getName()) {
            case "next" -> { boolean value = next[0]; next[0] = false; yield value; }
            case "getString" -> switch ((int) a[0]) { case 1 -> "0042123456"; case 2 -> "CONTRACT"; case 3 -> "RF "; case 9 -> "I"; default -> throw new AssertionError(); };
            case "getBigDecimal" -> (int) a[0] == 4 ? new BigDecimal("-120.12") : new BigDecimal("42.50");
            case "getInt" -> (int) a[0] == 5 ? -32768 : 32767;
            case "getObject" -> { assertEquals(8, a[0]); assertEquals(LocalDate.class, a[1]); yield LocalDate.of(1582, 10, 10); }
            case "wasNull" -> false;
            case "close" -> { closed[0] = true; yield null; }
            default -> throw new AssertionError(m.getName());
        });
        PreparedStatement statement = proxy(PreparedStatement.class, (p, m, a) -> switch (m.getName()) {
            case "setString", "setObject" -> { bound.put((Integer) a[0], a[1]); yield null; }
            case "executeQuery" -> result;
            case "close" -> { closed[1] = true; yield null; }
            default -> throw new AssertionError(m.getName());
        });
        Connection connection = proxy(Connection.class, (p, m, a) -> switch (m.getName()) {
            case "prepareStatement" -> { assertEquals(Fontes.SQL, a[0]); yield statement; }
            case "close" -> { closed[2] = true; yield null; }
            default -> throw new AssertionError(m.getName());
        });
        Driver driver = new Driver() {
            public Connection connect(String url, Properties properties) { return acceptsURL(url) ? connection : null; }
            public boolean acceptsURL(String url) { return url.startsWith("jdbc:fake-credit:"); }
            public DriverPropertyInfo[] getPropertyInfo(String u, Properties p) { return new DriverPropertyInfo[0]; }
            public int getMajorVersion() { return 1; }
            public int getMinorVersion() { return 0; }
            public boolean jdbcCompliant() { return false; }
            public Logger getParentLogger() { return Logger.getGlobal(); }
        };
        DriverManager.registerDriver(driver);
        try {
            try (var reader = Fontes.db2("jdbc:fake-credit:test", "user", "password", "0042123456", "0042", LocalDate.of(1582, 10, 10))) {
                assertEquals(Map.of(1, "0042123456", 2, "0042", 3, LocalDate.of(1582, 10, 10)), bound);
                Operacao op = reader.proxima();
                assertEquals("15821010", op.vencimento());
                assertEquals(new BigDecimal("-120.12"), op.original());
                assertEquals(new BigDecimal("42.50"), op.saldo());
                assertEquals(-32768, op.parcelas());
                assertEquals(32767, op.pagas());
                assertEquals("REAL ESTATE FINANCE", op.descricao());
                assertNull(reader.proxima());
            }
            assertArrayEquals(new boolean[]{true, true, true}, closed);
        } finally { DriverManager.deregisterDriver(driver); }
    }
}
