package dk.itu.datasys.sql;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dk.itu.datasys.ColumnSpec;
import dk.itu.datasys.ColumnType;
import dk.itu.datasys.StorageEngine;

class BinderIT {

    private static Binder binderWithTrips(Path dir) {
        StorageEngine engine = new StorageEngine(dir);
        engine.createTable("trips", List.of(
                new ColumnSpec("city", ColumnType.STRING),
                new ColumnSpec("distance", ColumnType.LONG),
                new ColumnSpec("price", ColumnType.DOUBLE)));
        return new Binder(engine);
    }

    private static void bind(Path dir, String sql) {
        Binder binder = binderWithTrips(dir);
        new SqlParser().parse(sql).forEach(binder::bind);
    }

    private static Binder binderWithShop(Path dir) {
        StorageEngine engine = new StorageEngine(dir);
        engine.createTable("orders", List.of(
                new ColumnSpec("o_orderkey", ColumnType.LONG),
                new ColumnSpec("o_custname", ColumnType.STRING),
                new ColumnSpec("o_prodname", ColumnType.STRING),
                new ColumnSpec("o_quantity", ColumnType.LONG)));
        engine.createTable("customers", List.of(
                new ColumnSpec("c_name", ColumnType.STRING),
                new ColumnSpec("c_city", ColumnType.STRING)));
        engine.createTable("products", List.of(
                new ColumnSpec("p_name", ColumnType.STRING),
                new ColumnSpec("p_price", ColumnType.DOUBLE)));
        engine.createTable("t1", List.of(new ColumnSpec("id", ColumnType.LONG)));
        engine.createTable("t2", List.of(new ColumnSpec("id", ColumnType.LONG)));
        return new Binder(engine);
    }

    private static void bindShop(Path dir, String sql) {
        Binder binder = binderWithShop(dir);
        new SqlParser().parse(sql).forEach(binder::bind);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM trips;",
            "SELECT * FROM trips WHERE distance > 100;",
            "SELECT * FROM trips WHERE city = 'Odense';",
            "SELECT * FROM trips WHERE price < 50.0;",
            "COPY trips FROM 'trips.csv';",
            "CREATE TABLE other (a STRING, b LONG);",
            "DELETE FROM trips;",
            "DELETE FROM trips WHERE city = 'Aarhus';",
            "DELETE FROM trips WHERE price > 50.0;",
    })
    void validStatementsBind(String sql, @TempDir Path dir) {
        assertDoesNotThrow(() -> bind(dir, sql));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM nope;",                          // unknown table
            "COPY nope FROM 'trips.csv';",                  // unknown table
            "SELECT * FROM trips WHERE nope = 1;",          // unknown column
            "SELECT * FROM trips WHERE distance = 'x';",    // String against LONG
            "SELECT * FROM trips WHERE distance = 1.0;",    // Double against LONG
            "SELECT * FROM trips WHERE city = 1;",          // Long against STRING
            "CREATE TABLE t (a STRING, a LONG);",           // duplicate column
            "DELETE FROM nope;",                            // unknown table
            "DELETE FROM trips WHERE nope = 1;",            // unknown column
            "DELETE FROM trips WHERE distance = 'x';",      // String against LONG
    })
    void invalidStatementsAreRejected(String sql, @TempDir Path dir) {
        assertThrows(IllegalArgumentException.class, () -> bind(dir, sql));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM orders, customers WHERE o_custname = c_name;",
            "SELECT * FROM customers, orders WHERE c_name = o_custname;",
            "SELECT * FROM orders, customers, products"
                    + " WHERE o_custname = c_name AND o_prodname = p_name AND c_city = 'Copenhagen';",
            "SELECT * FROM t1, t2;",
    })
    void validJoinsBind(String sql, @TempDir Path dir) {
        assertDoesNotThrow(() -> bindShop(dir, sql));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM orders, customers WHERE o_custname = c_namee;",      // unknown column
            "SELECT * FROM orders, nope WHERE o_custname = c_name;",           // unknown table
            "SELECT * FROM t1, t2 WHERE id = 1;",                              // ambiguous column
            "SELECT * FROM orders, products WHERE o_quantity = p_price;",      // LONG against DOUBLE
            "SELECT * FROM orders, customers WHERE o_custname = o_prodname;",  // same table
            "SELECT * FROM orders, customers WHERE c_city = 1;",               // Long against STRING
            "SELECT * FROM orders, orders;",                                   // table listed twice
    })
    void invalidJoinsAreRejected(String sql, @TempDir Path dir) {
        assertThrows(IllegalArgumentException.class, () -> bindShop(dir, sql));
    }

    @Test
    void aJoinWithinOneTableSaysSo(@TempDir Path dir) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> bindShop(dir, "SELECT * FROM orders, customers WHERE o_custname = o_prodname;"));

        assertTrue(e.getMessage().contains("join needs two tables: o_custname = o_prodname"),
                e.getMessage());
    }

    @Test
    void aJoinAcrossTypesSaysSo(@TempDir Path dir) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> bindShop(dir, "SELECT * FROM orders, products WHERE o_quantity = p_price;"));

        assertTrue(e.getMessage().contains("join needs two columns of one type: o_quantity = p_price"),
                e.getMessage());
    }

    @Test
    void anAmbiguousColumnSaysSo(@TempDir Path dir) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> bindShop(dir, "SELECT * FROM t1, t2 WHERE id = 1;"));

        assertTrue(e.getMessage().contains("ambiguous column: id"), e.getMessage());
    }
}
