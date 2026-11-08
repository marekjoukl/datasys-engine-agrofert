package dk.itu.datasys;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dk.itu.datasys.sql.Predicate;
import dk.itu.datasys.sql.SelectStatement;

class ConflictIT {

    private static final long TIMEOUT_SECONDS = 10;

    private static final String SORTED_TRIPS = "src/test/resources/trips_sorted.csv";
    private static final String MORE_TRIPS = "src/test/resources/trips.csv";

    private static final List<ColumnSpec> TRIPS = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    private static final Optional<Predicate> ODENSE =
            Optional.of(new Predicate("city", Comparison.EQUALS, "Odense"));

    @Test
    void aScanOpenedBeforeADeleteReturnsThePinnedRows(@TempDir Path dir) {
        StorageEngine engine = tripsAfter(dir, SORTED_TRIPS);
        List<List<Object>> pinnedRows = selectAll(engine);
        Operator scan = new Planner(engine).plan(new SelectStatement("trips", Optional.empty())).root();

        scan.open();
        engine.delete("trips", ODENSE);
        List<List<Object>> rows = drainOpened(scan);

        assertEquals(sorted(pinnedRows), sorted(rows));
        assertEquals(7, selectAll(engine).size(), "a new SELECT sees the delete");
    }

    @Test
    void aParkedDeleteAbortsWhenACopyCommitsFirst(@TempDir Path dir, @TempDir Path reference)
            throws Exception {
        StorageEngine engine = tripsAfter(dir, SORTED_TRIPS);

        ConflictException conflict = parkedLoserFails(engine,
                () -> engine.delete("trips", ODENSE),
                () -> engine.copyFile("trips", MORE_TRIPS));

        assertEquals(1, conflict.base());
        assertEquals(2, conflict.current());
        assertEquals(2, engine.snapshot("trips").version());
        assertEquals(sorted(selectAll(tripsAfter(reference, SORTED_TRIPS, MORE_TRIPS))),
                sorted(selectAll(engine)));
    }

    @Test
    void aParkedCopyAbortsWhenAnotherCopyCommitsFirst(@TempDir Path dir, @TempDir Path reference)
            throws Exception {
        StorageEngine engine = tripsAfter(dir, SORTED_TRIPS);

        ConflictException conflict = parkedLoserFails(engine,
                () -> engine.copyFile("trips", MORE_TRIPS),
                () -> engine.copyFile("trips", SORTED_TRIPS));

        assertEquals(1, conflict.base());
        assertEquals(2, conflict.current());
        assertEquals(2, engine.snapshot("trips").version());
        assertEquals(sorted(selectAll(tripsAfter(reference, SORTED_TRIPS, SORTED_TRIPS))),
                sorted(selectAll(engine)));
    }

    @Test
    void anAbortedDeleteLeavesTheWinnersTableAndSucceedsWhenRerun(@TempDir Path dir,
            @TempDir Path reference) {
        StorageEngine engine = tripsAfter(dir, SORTED_TRIPS);
        engine.beforeCommit = () -> {
            engine.beforeCommit = () -> { };
            engine.copyFile("trips", MORE_TRIPS);
        };

        assertThrows(ConflictException.class, () -> engine.delete("trips", ODENSE));
        assertEquals(sorted(selectAll(tripsAfter(reference, SORTED_TRIPS, MORE_TRIPS))),
                sorted(selectAll(engine)));

        DeleteResult rerun = engine.delete("trips", ODENSE);

        assertEquals(2, rerun.rowsDeleted());
        assertEquals(3, rerun.version());
        assertEquals(14, selectAll(engine).size());
        assertTrue(selectAll(engine).stream().noneMatch(row -> row.get(0).equals("Odense")));
    }

    @Test
    void aWriterThatReservedFirstNeverOverwritesTheBytesOfALaterWriter(@TempDir Path dir,
            @TempDir Path reference) {
        StorageEngine engine = tripsAfter(dir, SORTED_TRIPS);
        engine.afterReserve = () -> {
            engine.afterReserve = () -> { };
            engine.copyFile("trips", MORE_TRIPS);
        };

        assertThrows(ConflictException.class, () -> engine.copyFile("trips", SORTED_TRIPS));

        assertEquals(sorted(selectAll(tripsAfter(reference, SORTED_TRIPS, MORE_TRIPS))),
                sorted(selectAll(engine)));
    }

    private static ConflictException parkedLoserFails(StorageEngine engine, Callable<?> loser,
            Runnable winner) throws Exception {
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch winnerDone = new CountDownLatch(1);
        engine.beforeCommit = () -> {
            parked.countDown();
            await(winnerDone);
        };

        try (ExecutorService writers = Executors.newSingleThreadExecutor()) {
            Future<?> parkedLoser = writers.submit(loser);
            assertTrue(parked.await(TIMEOUT_SECONDS, SECONDS), "the loser never reached its commit");
            engine.beforeCommit = () -> { };
            winner.run();
            winnerDone.countDown();

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> parkedLoser.get(TIMEOUT_SECONDS, SECONDS));
            return assertInstanceOf(ConflictException.class, failure.getCause());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, SECONDS)) {
                throw new AssertionError("timed out after " + TIMEOUT_SECONDS + " s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static StorageEngine tripsAfter(Path dir, String... csvFiles) {
        StorageEngine engine = new StorageEngine(dir, 2);
        engine.createTable("trips", TRIPS);
        for (String csvFile : csvFiles) {
            engine.copyFile("trips", csvFile);
        }
        return engine;
    }

    private static List<List<Object>> selectAll(StorageEngine engine) {
        Operator scan = new Planner(engine).plan(new SelectStatement("trips", Optional.empty())).root();
        scan.open();
        return drainOpened(scan);
    }

    private static List<List<Object>> drainOpened(Operator operator) {
        List<List<Object>> rows = new ArrayList<>();
        try {
            for (Object[] row = operator.next(); row != null; row = operator.next()) {
                rows.add(Arrays.asList(row));
            }
        } finally {
            operator.close();
        }
        return rows;
    }

    private static List<List<Object>> sorted(List<List<Object>> rows) {
        return rows.stream().sorted(Comparator.comparing(Object::toString)).toList();
    }
}
