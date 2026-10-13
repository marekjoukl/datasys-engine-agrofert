package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dk.itu.datasys.sql.Predicate;
import dk.itu.datasys.sql.SelectStatement;

class PlannerIT {

    private static final String SORTED_CSV = "src/test/resources/trips_sorted.csv";

    private static final List<ColumnSpec> TRIPS = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    @Test
    void prunesThePartitionsThatCannotHoldAMatch(@TempDir Path dir) {
        Plan plan = planWhere(dir, "distance", Comparison.GREATER_THAN, 100L);

        assertEquals(new ScanStats(4, 2, 2), plan.scanStats());
        assertEquals(List.of(
                List.of("Copenhagen", 140L, 210.0),
                List.of("Aarhus", 187L, 301.0),
                List.of("Aalborg", 210L, 340.5),
                List.of("Esbjerg", 299L, 450.25)),
                drain(plan.root()));
    }

    @Test
    void readsEveryPartitionWhenNoneCanBePruned(@TempDir Path dir) {
        Plan plan = planWhere(dir, "distance", Comparison.GREATER_THAN, -1L);

        assertEquals(new ScanStats(4, 4, 0), plan.scanStats());
        assertEquals(8, drain(plan.root()).size());
    }

    @Test
    void prunesEveryPartitionWhenNoneCanMatch(@TempDir Path dir) {
        Plan plan = planWhere(dir, "distance", Comparison.GREATER_THAN, 1000L);

        assertEquals(new ScanStats(4, 0, 4), plan.scanStats());
        assertEquals(List.of(), drain(plan.root()));
    }

    @Test
    void prunesOnStringRangesToo(@TempDir Path dir) {
        Plan plan = planWhere(dir, "city", Comparison.EQUALS, "Odense");

        assertEquals(new ScanStats(4, 2, 2), plan.scanStats());
        assertEquals(List.of(List.of("Odense", 95L, 120.75)), drain(plan.root()));
    }

    @Test
    void aWhereClauseProducesAFilterOverAScan(@TempDir Path dir) {
        Plan plan = planWhere(dir, "distance", Comparison.GREATER_THAN, 100L);

        assertInstanceOf(FilterOperator.class, plan.root());
    }

    @Test
    void noWhereClauseProducesABareScanOverEveryPartition(@TempDir Path dir) {
        Plan plan = new Planner(copy(dir)).plan(new SelectStatement("trips", Optional.empty()));

        assertInstanceOf(ScanOperator.class, plan.root());
        assertEquals(new ScanStats(4, 4, 0), plan.scanStats());
        assertEquals(8, drain(plan.root()).size());
    }

    @Test
    void rejectsAnUnknownTable(@TempDir Path dir) {
        Planner planner = new Planner(copy(dir));

        assertThrows(IllegalArgumentException.class,
                () -> planner.plan(new SelectStatement("nope", Optional.empty())));
    }

    @Test
    void rejectsAnUnknownColumn(@TempDir Path dir) {
        Planner planner = new Planner(copy(dir));
        SelectStatement select = new SelectStatement("trips",
                Optional.of(new Predicate("nope", Comparison.EQUALS, 1L)));

        assertThrows(IllegalArgumentException.class, () -> planner.plan(select));
    }

    private static Plan planWhere(Path dir, String column, Comparison comparison, Object constant) {
        return new Planner(copy(dir)).plan(new SelectStatement("trips",
                Optional.of(new Predicate(column, comparison, constant))));
    }

    private static StorageEngine copy(Path dir) {
        StorageEngine engine = new StorageEngine(dir, 2);
        engine.createTable("trips", TRIPS);
        engine.copyFile("trips", SORTED_CSV);
        return engine;
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
