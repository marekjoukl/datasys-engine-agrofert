package dk.itu.datasys.sql;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;

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

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM trips;",
            "SELECT * FROM trips WHERE distance > 100;",
            "SELECT * FROM trips WHERE city = 'Odense';",
            "SELECT * FROM trips WHERE price < 50.0;",
            "COPY trips FROM 'trips.csv';",
            "CREATE TABLE other (a STRING, b LONG);",
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
    })
    void invalidStatementsAreRejected(String sql, @TempDir Path dir) {
        assertThrows(IllegalArgumentException.class, () -> bind(dir, sql));
    }
}
