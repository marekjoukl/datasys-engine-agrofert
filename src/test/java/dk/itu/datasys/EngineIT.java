package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import dk.itu.datasys.sql.SqlParser;
import dk.itu.datasys.sql.Statement;

class EngineIT {

    private static List<String> runDemo() {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            Engine.main(new String[0]);
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8).lines().toList();
    }

    @Test
    void theDemoPrintsOneStatementPerLine() {
        assertEquals(
                List.of("CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);",
                        "COPY trips FROM 'trips.csv';",
                        "SELECT * FROM trips WHERE distance > 100;",
                        "SELECT * FROM trips;"),
                runDemo());
    }
    @Test
    void theDemoDropsComments() {
        assertEquals(0, runDemo().stream().filter(line -> line.contains("--")).count(),
                "printed SQL is rendered from the AST, which holds no comments");
    }

    @Test
    void theDemoCanBeRunTwice() {
        runDemo();

        assertEquals(4, runDemo().size());
    }

    @Test
    void theDemoOutputRoundTrips() {
        SqlParser parser = new SqlParser();

        List<Statement> original = parser.parse(Engine.EXAMPLE_SCRIPT);
        List<Statement> reparsed = parser.parse(String.join("\n", runDemo()));

        assertEquals(original, reparsed);
    }
}
