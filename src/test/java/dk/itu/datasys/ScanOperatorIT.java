package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScanOperatorIT {

    private static final String SORTED_CSV = "src/test/resources/trips_sorted.csv";

    private static final List<ColumnSpec> TRIPS = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    @Test
    void readsEveryRowOfEveryPartitionInDiskOrder(@TempDir Path dir) {
        StorageEngine engine = copy(dir, 2);

        assertEquals(4, engine.partitionCount("trips"));
        assertEquals(List.of(
                List.of("Copenhagen", 12L, 23.5),
                List.of("Roskilde", 31L, 45.0),
                List.of("Copenhagen", 88L, 99.99),
                List.of("Odense", 95L, 120.75),
                List.of("Copenhagen", 140L, 210.0),
                List.of("Aarhus", 187L, 301.0),
                List.of("Aalborg", 210L, 340.5),
                List.of("Esbjerg", 299L, 450.25)),
                drain(new ScanOperator(engine, "trips", pick(engine, 0, 1, 2, 3))));
    }

    @Test
    void readsOnlyTheRowsOfTheGivenPartition(@TempDir Path dir) {
        StorageEngine engine = copy(dir, 2);

        assertEquals(List.of(
                List.of("Copenhagen", 140L, 210.0),
                List.of("Aarhus", 187L, 301.0)),
                drain(new ScanOperator(engine, "trips", pick(engine, 2))));
    }

    @Test
    void readsNothingWhenEveryPartitionWasPruned(@TempDir Path dir) {
        StorageEngine engine = copy(dir, 2);

        assertEquals(List.of(), drain(new ScanOperator(engine, "trips", pick(engine))));
    }

    @Test
    void followsTheOrderOfTheGivenPartitionList(@TempDir Path dir) {
        StorageEngine engine = copy(dir, 2);

        assertEquals(List.of(
                List.of("Aalborg", 210L, 340.5),
                List.of("Esbjerg", 299L, 450.25),
                List.of("Copenhagen", 12L, 23.5),
                List.of("Roskilde", 31L, 45.0)),
                drain(new ScanOperator(engine, "trips", pick(engine, 3, 0))));
    }

    @Test
    void canBeDrainedTwiceBecauseOpenResets(@TempDir Path dir) {
        StorageEngine engine = copy(dir, 2);
        ScanOperator operator = new ScanOperator(engine, "trips", pick(engine, 1));

        List<List<Object>> first = drain(operator);

        assertEquals(2, first.size());
        assertEquals(first, drain(operator));
    }

    private static StorageEngine copy(Path dir, int maxRowsPerPartition) {
        StorageEngine engine = new StorageEngine(dir, maxRowsPerPartition);
        engine.createTable("trips", TRIPS);
        engine.copyFile("trips", SORTED_CSV);
        return engine;
    }

    private static List<PartitionMeta> pick(StorageEngine engine, int... numbers) {
        List<PartitionMeta> partitions = engine.snapshot("trips").partitions();
        return Arrays.stream(numbers).mapToObj(partitions::get).toList();
    }

    private static List<List<Object>> drain(Operator operator) {
        List<Object[]> rows = new ArrayList<>();
        operator.open();
        try {
            for (Object[] row = operator.next(); row != null; row = operator.next()) {
                rows.add(row);
            }
        } finally {
            operator.close();
        }
        return rows.stream().map(Arrays::asList).toList();
    }
}
