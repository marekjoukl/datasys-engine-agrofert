package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SelectIT {

    private static final String GOLDEN_CSV = "src/test/resources/trips.csv";
    private static final String SORTED_CSV = "src/test/resources/trips_sorted.csv";

    private static final List<ColumnSpec> TRIPS = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    @Test
    void roundTripReturnsEveryRowInDiskOrder(@TempDir Path dir) {
        StorageEngine engine = copy(dir, GOLDEN_CSV, 2);

        List<Object[]> rows = engine.select("trips", "distance", Comparison.GREATER_THAN, -1L);

        assertEquals(List.of(
                List.of("Copenhagen", 12L, 23.5),
                List.of("Aarhus", 187L, 301.0),
                List.of("Odense", 95L, 120.75),
                List.of("Copenhagen", 140L, 210.0),
                List.of("Aalborg", 210L, 340.5),
                List.of("Roskilde", 31L, 45.0),
                List.of("Copenhagen", 88L, 99.99),
                List.of("Esbjerg", 299L, 450.25)),
                asLists(rows));
    }

    @Test
    void roundTripReturnsValuesTypedBySchema(@TempDir Path dir) {
        StorageEngine engine = copy(dir, GOLDEN_CSV, 2);

        Object[] row = engine.select("trips", "distance", Comparison.GREATER_THAN, -1L).get(0);

        assertInstanceOf(String.class, row[0]);
        assertInstanceOf(Long.class, row[1]);
        assertInstanceOf(Double.class, row[2]);
    }

    @Test
    void allComparisonsAgainstAStringColumn(@TempDir Path dir) {
        StorageEngine engine = copy(dir, GOLDEN_CSV, 2);

        assertEquals(List.of(
                List.of("Copenhagen", 12L, 23.5),
                List.of("Copenhagen", 140L, 210.0),
                List.of("Copenhagen", 88L, 99.99)),
                asLists(engine.select("trips", "city", Comparison.EQUALS, "Copenhagen")));

        assertEquals(List.of(
                List.of("Aarhus", 187L, 301.0),
                List.of("Aalborg", 210L, 340.5)),
                asLists(engine.select("trips", "city", Comparison.LESS_THAN, "Copenhagen")));

        assertEquals(List.of(
                List.of("Odense", 95L, 120.75),
                List.of("Roskilde", 31L, 45.0),
                List.of("Esbjerg", 299L, 450.25)),
                asLists(engine.select("trips", "city", Comparison.GREATER_THAN, "Copenhagen")));
    }

    @Test
    void allComparisonsAgainstALongColumn(@TempDir Path dir) {
        StorageEngine engine = copy(dir, GOLDEN_CSV, 2);

        assertEquals(List.of(
                List.of("Copenhagen", 140L, 210.0)),
                asLists(engine.select("trips", "distance", Comparison.EQUALS, 140L)));

        assertEquals(List.of(
                List.of("Copenhagen", 12L, 23.5),
                List.of("Odense", 95L, 120.75),
                List.of("Roskilde", 31L, 45.0),
                List.of("Copenhagen", 88L, 99.99)),
                asLists(engine.select("trips", "distance", Comparison.LESS_THAN, 100L)));

        assertEquals(List.of(
                List.of("Aarhus", 187L, 301.0),
                List.of("Copenhagen", 140L, 210.0),
                List.of("Aalborg", 210L, 340.5),
                List.of("Esbjerg", 299L, 450.25)),
                asLists(engine.select("trips", "distance", Comparison.GREATER_THAN, 100L)));
    }

    @Test
    void allComparisonsAgainstADoubleColumn(@TempDir Path dir) {
        StorageEngine engine = copy(dir, GOLDEN_CSV, 2);

        assertEquals(List.of(
                List.of("Odense", 95L, 120.75)),
                asLists(engine.select("trips", "price", Comparison.EQUALS, 120.75)));

        assertEquals(List.of(
                List.of("Copenhagen", 12L, 23.5),
                List.of("Roskilde", 31L, 45.0)),
                asLists(engine.select("trips", "price", Comparison.LESS_THAN, 50.0)));

        assertEquals(List.of(
                List.of("Aarhus", 187L, 301.0),
                List.of("Aalborg", 210L, 340.5),
                List.of("Esbjerg", 299L, 450.25)),
                asLists(engine.select("trips", "price", Comparison.GREATER_THAN, 300.0)));
    }

    @Test
    void aPredicateMatchingNothingReturnsAnEmptyList(@TempDir Path dir) {
        StorageEngine engine = copy(dir, GOLDEN_CSV, 2);

        assertEquals(List.of(), engine.select("trips", "distance", Comparison.GREATER_THAN, 1000L));
        assertEquals(List.of(), engine.select("trips", "city", Comparison.EQUALS, "Vejle"));
    }

    @Test
    void unknownTableIsRejected(@TempDir Path dir) {
        StorageEngine engine = copy(dir, GOLDEN_CSV, 2);

        assertThrows(
                IllegalArgumentException.class,
                () -> engine.select("nope", "distance", Comparison.EQUALS, 12L));
    }

    @Test
    void unknownColumnIsRejected(@TempDir Path dir) {
        StorageEngine engine = copy(dir, GOLDEN_CSV, 2);

        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> engine.select("trips", "duration", Comparison.EQUALS, 12L));

        assertTrue(e.getMessage().contains("duration"), e.getMessage());
    }

    @Test
    void aConstantOfTheWrongTypeIsRejected(@TempDir Path dir) {
        StorageEngine engine = copy(dir, GOLDEN_CSV, 2);

        assertThrows(
                IllegalArgumentException.class,
                () -> engine.select("trips", "distance", Comparison.EQUALS, "12"));
        assertThrows(
                IllegalArgumentException.class,
                () -> engine.select("trips", "city", Comparison.EQUALS, 12L));
        assertThrows(
                IllegalArgumentException.class,
                () -> engine.select("trips", "price", Comparison.EQUALS, 12L));
    }

    @Test
    void anIntegerConstantDoesNotWidenToALongColumn(@TempDir Path dir) {
        StorageEngine engine = copy(dir, GOLDEN_CSV, 2);

        assertThrows(
                IllegalArgumentException.class,
                () -> engine.select("trips", "distance", Comparison.EQUALS, 140));
    }

    @Test
    void aSelectivePredicatePrunesPartitions(@TempDir Path dir) {
        StorageEngine engine = copy(dir, SORTED_CSV, 2);

        List<Object[]> rows = engine.select("trips", "distance", Comparison.GREATER_THAN, 200L);

        assertEquals(List.of(
                List.of("Aalborg", 210L, 340.5),
                List.of("Esbjerg", 299L, 450.25)),
                asLists(rows));

        ScanStats stats = engine.lastScanStats();
        assertEquals(4, stats.partitionsTotal());
        assertEquals(1, stats.partitionsRead());
        assertEquals(3, stats.partitionsPruned());
    }

    @Test
    void aStringPredicateOutsideAPartitionRangePrunesIt(@TempDir Path dir) {
        StorageEngine engine = copy(dir, GOLDEN_CSV, 2);

        List<Object[]> rows = engine.select("trips", "city", Comparison.EQUALS, "Odense");

        assertEquals(List.of(List.of("Odense", 95L, 120.75)), asLists(rows));

        // [Aarhus Copenhagen] and [Copenhagen Esbjerg] both end before Odense.
        ScanStats stats = engine.lastScanStats();
        assertEquals(4, stats.partitionsTotal());
        assertEquals(2, stats.partitionsRead());
        assertEquals(2, stats.partitionsPruned());
    }

    @Test
    void scanStatsAccountForEveryPartition(@TempDir Path dir) {
        StorageEngine engine = copy(dir, GOLDEN_CSV, 2);

        engine.select("trips", "city", Comparison.EQUALS, "Copenhagen");
        ScanStats stats = engine.lastScanStats();

        // A [Aalborg Roskilde] partition can hold Copenhagen so nothing is pruned here.
        assertEquals(4, stats.partitionsTotal());
        assertEquals(4, stats.partitionsRead());
        assertEquals(stats.partitionsTotal(), stats.partitionsRead() + stats.partitionsPruned());
    }

    @Test
    void theThreeGoldenQueriesReturnTheDocumentedRows(@TempDir Path dir) {
        StorageEngine engine = copy(dir, GOLDEN_CSV, 2);

        assertEquals(
                List.of(187L, 140L, 210L, 299L),
                distances(engine.select("trips", "distance", Comparison.GREATER_THAN, 100L)));
        assertEquals(
                List.of(12L, 140L, 88L),
                distances(engine.select("trips", "city", Comparison.EQUALS, "Copenhagen")));
        assertEquals(
                List.of(12L, 31L),
                distances(engine.select("trips", "price", Comparison.LESS_THAN, 50.0)));
    }

    @Test
    void dataSurvivesRestart(@TempDir Path dir) {
        copy(dir, GOLDEN_CSV, 2);

        StorageEngine restarted = new StorageEngine(dir, 2);

        assertEquals(
                List.of(
                        List.of("Aarhus", 187L, 301.0),
                        List.of("Copenhagen", 140L, 210.0),
                        List.of("Aalborg", 210L, 340.5),
                        List.of("Esbjerg", 299L, 450.25)),
                asLists(restarted.select("trips", "distance", Comparison.GREATER_THAN, 100L)));
    }

    private static StorageEngine copy(Path dir, String csv, int maxRowsPerPartition) {
        StorageEngine engine = new StorageEngine(dir, maxRowsPerPartition);
        engine.createTable("trips", TRIPS);
        engine.copyFile("trips", csv);
        return engine;
    }

    private static List<List<Object>> asLists(List<Object[]> rows) {
        return rows.stream().map(Arrays::asList).toList();
    }

    private static List<Object> distances(List<Object[]> rows) {
        return rows.stream().map(row -> row[1]).toList();
    }
}
