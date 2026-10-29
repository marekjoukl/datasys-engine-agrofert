package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dk.itu.datasys.sql.Predicate;
import dk.itu.datasys.sql.SelectStatement;

class DeleteIT {

    // distance per partition with maxRowsPerPartition = 2:
    // [12, 31] [88, 95] [140, 187] [210, 299]
    private static final String SORTED_CSV = "src/test/resources/trips_sorted.csv";

    private static final List<ColumnSpec> TRIPS = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    @Test
    void createCopyCopyDeleteProduceVersionsZeroToThree(@TempDir Path dir) {
        StorageEngine engine = new StorageEngine(dir, 2);

        engine.createTable("trips", TRIPS);
        assertEquals(0, engine.snapshot("trips").version());
        assertEquals(0, selectAll(engine).size());

        assertEquals(1, engine.copyFile("trips", SORTED_CSV).version());
        assertEquals(8, selectAll(engine).size());

        assertEquals(2, engine.copyFile("trips", SORTED_CSV).version());
        assertEquals(16, selectAll(engine).size());

        assertEquals(3, engine.delete("trips", where("city", Comparison.EQUALS, "Copenhagen")).version());
        assertEquals(10, selectAll(engine).size());
        assertTrue(selectAll(engine).stream().noneMatch(row -> row.get(0).equals("Copenhagen")));
    }

    @Test
    void aSelectiveDeleteRewritesOnlyThePartitionsThatCanMatch(@TempDir Path dir) {
        StorageEngine engine = copy(dir);
        List<PartitionMeta> before = engine.snapshot("trips").partitions();

        DeleteResult result = engine.delete("trips", where("distance", Comparison.EQUALS, 95L));

        assertEquals(new DeleteResult(1, 1, 3, 2), result);
        List<PartitionMeta> after = engine.snapshot("trips").partitions();
        assertEquals(4, after.size());
        assertEquals(before.get(0), after.get(0), "carried partitions keep their offsets");
        assertEquals(before.get(2), after.get(2));
        assertEquals(before.get(3), after.get(3));

        PartitionMeta rewritten = after.get(1);
        assertEquals(1, rewritten.rowCount());
        assertEquals("88", rewritten.chunks().get("distance").min());
        assertEquals("88", rewritten.chunks().get("distance").max());
        assertTrue(rewritten.chunks().get("city").offset() > before.get(3).chunks().get("price").offset(),
                "the survivors are appended after the existing partitions");
    }

    @Test
    void aPartitionThatMayMatchButDoesNotIsCarriedAndNoVersionIsCommitted(@TempDir Path dir)
            throws Exception {
        StorageEngine engine = copy(dir);
        Snapshot before = engine.snapshot("trips");
        long sizeBefore = Files.size(dir.resolve("trips").resolve("trips.data"));

        DeleteResult result = engine.delete("trips", where("distance", Comparison.EQUALS, 90L));

        assertEquals(new DeleteResult(0, 0, 4, 1), result);
        assertEquals(before, engine.snapshot("trips"));
        assertEquals(sizeBefore, Files.size(dir.resolve("trips").resolve("trips.data")));
    }

    @Test
    void aPartitionWhoseRowsAllMatchIsDroppedWithoutWritingAnything(@TempDir Path dir)
            throws Exception {
        StorageEngine engine = copy(dir);
        List<PartitionMeta> before = engine.snapshot("trips").partitions();
        long sizeBefore = Files.size(dir.resolve("trips").resolve("trips.data"));

        DeleteResult result = engine.delete("trips", where("distance", Comparison.LESS_THAN, 40L));

        assertEquals(new DeleteResult(2, 1, 3, 2), result);
        assertEquals(before.subList(1, 4), engine.snapshot("trips").partitions());
        assertEquals(sizeBefore, Files.size(dir.resolve("trips").resolve("trips.data")));
    }

    @Test
    void aDeleteWithoutWhereEmptiesTheTable(@TempDir Path dir) {
        StorageEngine engine = copy(dir);

        DeleteResult result = engine.delete("trips", Optional.empty());

        assertEquals(new DeleteResult(8, 4, 0, 2), result);
        assertEquals(new Snapshot("trips", 2, List.of()), engine.snapshot("trips"));
    }

    @Test
    void aDeleteOnAnEmptyTableCommitsNoVersion(@TempDir Path dir) {
        StorageEngine engine = new StorageEngine(dir, 2);
        engine.createTable("trips", TRIPS);

        assertEquals(new DeleteResult(0, 0, 0, 0), engine.delete("trips", Optional.empty()));
        assertEquals(0, engine.snapshot("trips").version());
    }

    @Test
    void aSelectPlannedBeforeADeleteStillSeesTheDeletedRows(@TempDir Path dir) {
        StorageEngine engine = copy(dir);
        Plan pinned = new Planner(engine).plan(new SelectStatement("trips", Optional.empty()));

        engine.delete("trips", where("city", Comparison.EQUALS, "Odense"));

        List<List<Object>> rows = drain(pinned.root());
        assertEquals(8, rows.size());
        assertTrue(rows.contains(List.of("Odense", 95L, 120.75)));
        assertEquals(7, selectAll(engine).size(), "a new SELECT sees the delete");
    }

    @Test
    void aRestartedEngineSeesThePostDeleteVersion(@TempDir Path dir) {
        StorageEngine engine = copy(dir);
        engine.delete("trips", where("distance", Comparison.EQUALS, 95L));

        StorageEngine restarted = new StorageEngine(dir, 2);

        assertEquals(engine.snapshot("trips"), restarted.snapshot("trips"));
        assertEquals(2, restarted.snapshot("trips").version());
        assertEquals(7, selectAll(restarted).size());
    }

    private static StorageEngine copy(Path dir) {
        StorageEngine engine = new StorageEngine(dir, 2);
        engine.createTable("trips", TRIPS);
        engine.copyFile("trips", SORTED_CSV);
        return engine;
    }

    private static Optional<Predicate> where(String column, Comparison comparison, Object constant) {
        return Optional.of(new Predicate(column, comparison, constant));
    }

    private static List<List<Object>> selectAll(StorageEngine engine) {
        return drain(new Planner(engine).plan(new SelectStatement("trips", Optional.empty())).root());
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
