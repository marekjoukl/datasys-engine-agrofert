package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

class LoggingIT {

    private static final String SETUP = """
            CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
            COPY trips FROM 'src/test/resources/trips_sorted.csv';
            """;

    private static final String LONG_TRIPS = "SELECT * FROM trips WHERE distance > 200;";

    @Test
    void everyStatementEmitsSevenFieldCsvLines(@TempDir Path dir) throws Exception {
        List<String> lines = run(dir, LONG_TRIPS);

        assertFalse(lines.isEmpty(), "no log lines were written for this session");
        for (String line : lines) {
            assertEquals(7, line.split(",", -1).length, line);
        }
    }

    @Test
    void everyMinMaxCreatedDuringCopyIsLogged(@TempDir Path dir) throws Exception {
        List<String> lines = run(dir, "");

        assertEquals(12, messages(lines).stream().filter(m -> m.contains(" min=")).count());
        assertTrue(messages(lines).contains("table=trips partition=0 column=distance min=12 max=31"),
                messages(lines).toString());
        assertTrue(messages(lines).contains("table=trips partition=3 column=city min=Aalborg max=Esbjerg"),
                messages(lines).toString());
    }

    @Test
    void everyPruneOrReadDecisionIsLogged(@TempDir Path dir) throws Exception {
        List<String> lines = run(dir, LONG_TRIPS);

        List<String> decisions = messages(lines).stream()
                .filter(m -> m.contains("decision="))
                .toList();

        assertEquals(4, decisions.size());
        assertEquals(3, decisions.stream().filter(m -> m.endsWith("decision=PRUNED")).count());
        assertEquals(1, decisions.stream().filter(m -> m.endsWith("decision=READ")).count());
        assertTrue(decisions.get(0).contains("comparison=GREATER_THAN const=200 partition=0 min=12 max=31"),
                decisions.get(0));
    }

    @Test
    void theSelectSummaryLineCarriesTheScanStats(@TempDir Path dir) throws Exception {
        List<String> lines = run(dir, LONG_TRIPS);

        assertTrue(
                messages(lines).stream().anyMatch(m -> m.startsWith(
                        "statement=SELECT table=trips rowsOut=2 partitionsRead=1 partitionsPruned=3")),
                messages(lines).toString());
    }

    @Test
    void theSelectSummaryLineTimesTheQueryWithoutTheOutput(@TempDir Path dir) throws Exception {
        List<String> lines = run(dir, LONG_TRIPS);

        String summary = messages(lines).stream()
                .filter(m -> m.startsWith("statement=SELECT"))
                .findFirst()
                .orElseThrow();

        long queryMs = valueOf(summary, "queryMs");
        long durationMs = valueOf(summary, "durationMs");
        assertTrue(queryMs >= 0 && queryMs <= durationMs, summary);
    }

    @Test
    void aConstantContainingACommaCannotBreakTheLineFormat(@TempDir Path dir) throws Exception {
        List<String> lines = run(dir, "SELECT * FROM trips WHERE city = 'Vejle,Odense';");

        for (String line : lines) {
            assertEquals(7, line.split(",", -1).length, line);
        }
        assertTrue(messages(lines).stream().anyMatch(m -> m.contains("const=Vejle_Odense")),
                messages(lines).toString());
    }

    @Test
    void aFailingStatementLeavesAnErrorLineForItsOwnStatementNumber(@TempDir Path dir)
            throws Exception {
        String sessionId = startSession();
        int status;
        try {
            status = Engine.run(new String[] {"-c", "SELECT * FROM nosuchtable"}, discard(), dir);
        } finally {
            MDC.clear();
        }

        assertEquals(1, status);

        List<String> errors = linesFor(sessionId).stream()
                .filter(line -> line.split(",", 7)[4].equals("ERROR"))
                .toList();

        assertEquals(1, errors.size(), linesFor(sessionId).toString());
        assertEquals("1", errors.get(0).split(",", 7)[2], errors.get(0));
        assertTrue(errors.get(0).contains("message=unknown table: nosuchtable"), errors.get(0));
    }

    private static List<String> run(Path dir, String sql) throws Exception {
        String sessionId = startSession();
        try {
            new Executor(new StorageEngine(dir, 2), discard()).run(SETUP + sql);
        } finally {
            MDC.clear();
        }
        return linesFor(sessionId);
    }

    private static String startSession() {
        String sessionId = UUID.randomUUID().toString();
        MDC.put("sessionId", sessionId);
        MDC.put("statementNumber", "0");
        return sessionId;
    }

    private static PrintStream discard() {
        return new PrintStream(OutputStream.nullOutputStream());
    }

    private static List<String> linesFor(String sessionId) throws Exception {
        return Files.readAllLines(Path.of("logs/engine.log")).stream()
                .filter(line -> line.contains(sessionId))
                .toList();
    }

    private static List<String> messages(List<String> lines) {
        return lines.stream().map(line -> line.split(",", 7)[6]).toList();
    }

    private static long valueOf(String message, String key) {
        for (String pair : message.split(" ")) {
            if (pair.startsWith(key + "=")) {
                return Long.parseLong(pair.substring(key.length() + 1));
            }
        }
        throw new AssertionError("no " + key + " in " + message);
    }
}
