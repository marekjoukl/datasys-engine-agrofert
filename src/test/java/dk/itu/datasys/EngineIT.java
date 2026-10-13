package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineIT {

    private static final String TRIPS_CSV = "src/test/resources/trips_sorted.csv";

    private static final String SETUP = """
            CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
            COPY trips FROM '%s';
            """.formatted(TRIPS_CSV);

    private record Result(int status, String out, String err) { }

    @Test
    void aScriptPrintsItsRowsAsHeaderlessCsv(@TempDir Path dir) {
        Result result = run(dir, "-f", script(dir, SETUP + "SELECT * FROM trips WHERE distance > 100;"));

        assertEquals(0, result.status());
        assertEquals(csv(
                "Copenhagen,140,210.0",
                "Aarhus,187,301.0",
                "Aalborg,210,340.5",
                "Esbjerg,299,450.25"),
                result.out());
    }

    @Test
    void everyRowIsPrintedWhenThereIsNoWhereClause(@TempDir Path dir) {
        Result result = run(dir, "-f", script(dir, SETUP + "SELECT * FROM trips;"));

        assertEquals(0, result.status());
        assertEquals(8, result.out().lines().count());
    }

    @Test
    void aSingleStatementRunsWithoutATrailingSemicolon(@TempDir Path dir) {
        run(dir, "-f", script(dir, SETUP));

        Result result = run(dir, "-c", "SELECT * FROM trips WHERE city = 'Odense'");

        assertEquals(0, result.status());
        assertEquals(csv("Odense,95,120.75"), result.out());
    }

    @Test
    void aBareArgumentIsTreatedAsSql(@TempDir Path dir) {
        run(dir, "-f", script(dir, SETUP));

        Result result = run(dir, "SELECT * FROM trips WHERE price < 50.0");

        assertEquals(0, result.status());
        assertEquals(csv("Copenhagen,12,23.5", "Roskilde,31,45.0"), result.out());
    }

    @Test
    void noArgumentsPrintsTheTeamNameAndUsage(@TempDir Path dir) {
        Result result = run(dir);

        assertEquals(0, result.status());
        assertTrue(result.out().contains("agrofert"), result.out());
        assertTrue(result.out().contains("-f"), result.out());
        assertEquals("", result.err());
    }

    @Test
    void aFailingScriptLeavesStdoutCleanAndExitsOne(@TempDir Path dir) {
        Result result = run(dir, "-f", script(dir, "SELECT * FROM nope;"));

        assertEquals(1, result.status());
        assertEquals("", result.out());
        assertTrue(result.err().contains("unknown table: nope"), result.err());
    }

    @Test
    void statementsBeforeTheFailingOneStillPrinted(@TempDir Path dir) {
        Result result = run(dir, "-f", script(dir, SETUP
                + "SELECT * FROM trips WHERE distance > 250;\n"
                + "SELECT * FROM trips WHERE city = 12;\n"));

        assertEquals(1, result.status());
        assertEquals(csv("Esbjerg,299,450.25"), result.out());
        assertTrue(result.err().contains("city"), result.err());
    }

    @Test
    void anUnknownOptionExitsOne(@TempDir Path dir) {
        Result result = run(dir, "-x", "whatever");

        assertEquals(1, result.status());
        assertEquals("", result.out());
        assertTrue(result.err().contains("unknown option: -x"), result.err());
    }

    private static Result run(Path dataDirectory, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        int status;
        try {
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            status = Engine.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                    dataDirectory);
        } finally {
            System.setErr(originalErr);
        }
        return new Result(status, out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }

    private static String script(Path dir, String sql) {
        Path file = dir.resolve("script.sql");
        try {
            Files.writeString(file, sql);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file.toString();
    }

    private static String csv(String... lines) {
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }
}
