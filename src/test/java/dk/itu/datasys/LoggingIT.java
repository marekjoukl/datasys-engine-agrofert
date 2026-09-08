package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

class LoggingIT {

    private static final String SORTED_CSV = "src/test/resources/trips_sorted.csv";

    private static final List<ColumnSpec> TRIPS = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    @Test
    void everyApiCallEmitsSevenFieldCsvLines(@TempDir Path dir) throws Exception {
        List<String> lines = run(dir, engine ->
                engine.select("trips", "distance", Comparison.GREATER_THAN, 200L));

        assertFalse(lines.isEmpty(), "no log lines were written for this session");
        for (String line : lines) {
            assertEquals(7, line.split(",", -1).length, line);
        }
    }

    @Test
    void everyMinMaxCreatedDuringCopyIsLogged(@TempDir Path dir) throws Exception {
        List<String> lines = run(dir, engine -> { });

        // 4 partitions x 3 columns, plus the copyFile summary line.
        assertEquals(12, messages(lines).stream().filter(m -> m.contains(" min=")).count());
        assertTrue(messages(lines).contains("table=trips partition=0 column=distance min=12 max=31"),
                messages(lines).toString());
        assertTrue(messages(lines).contains("table=trips partition=3 column=city min=Aalborg max=Esbjerg"),
                messages(lines).toString());
    }

    @Test
    void everyPruneOrReadDecisionIsLogged(@TempDir Path dir) throws Exception {
        List<String> lines = run(dir, engine ->
                engine.select("trips", "distance", Comparison.GREATER_THAN, 200L));

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
        List<String> lines = run(dir, engine ->
                engine.select("trips", "distance", Comparison.GREATER_THAN, 200L));

        assertTrue(
                messages(lines).stream().anyMatch(m -> m.contains(
                        "partitionsTotal=4 partitionsRead=1 partitionsPruned=3 rowsOut=2")),
                messages(lines).toString());
    }

    @Test
    void aConstantContainingACommaCannotBreakTheLineFormat(@TempDir Path dir) throws Exception {
        List<String> lines = run(dir, engine ->
                engine.select("trips", "city", Comparison.EQUALS, "Vejle,Odense"));

        for (String line : lines) {
            assertEquals(7, line.split(",", -1).length, line);
        }
        assertTrue(messages(lines).stream().anyMatch(m -> m.contains("const=Vejle_Odense")),
                messages(lines).toString());
    }

    private interface Query {
        void run(StorageEngine engine);
    }

    private static List<String> run(Path dir, Query query) throws Exception {
        String sessionId = UUID.randomUUID().toString();
        MDC.put("sessionId", sessionId);
        MDC.put("statementNumber", "0");
        try {
            StorageEngine engine = new StorageEngine(dir, 2);
            engine.createTable("trips", TRIPS);
            engine.copyFile("trips", SORTED_CSV);
            query.run(engine);
        } finally {
            MDC.clear();
        }

        return Files.readAllLines(Path.of("logs/engine.log")).stream()
                .filter(line -> line.contains(sessionId))
                .toList();
    }

    private static List<String> messages(List<String> lines) {
        return lines.stream().map(line -> line.split(",", 7)[6]).toList();
    }
}
