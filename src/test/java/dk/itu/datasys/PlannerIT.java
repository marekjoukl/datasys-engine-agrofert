package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dk.itu.datasys.sql.JoinPredicate;
import dk.itu.datasys.sql.Predicate;
import dk.itu.datasys.sql.SelectStatement;

class PlannerIT {

    private static final String SORTED_CSV = "src/test/resources/trips_sorted.csv";

    private static final List<ColumnSpec> TRIPS = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    private static final List<String> TESTBED_TABLES = List.of("orders", "customers", "products", "cities");

    private static final List<JoinPredicate> TESTBED_JOINS = List.of(
            new JoinPredicate("o_custname", "c_name"),
            new JoinPredicate("o_prodname", "p_name"),
            new JoinPredicate("c_city", "ct_city"));

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
    void severalFiltersOnOneTableAllApplyAndAllPrune(@TempDir Path dir) {
        Plan plan = new Planner(copy(dir)).plan(new SelectStatement(List.of("trips"), List.of(
                new Predicate("distance", Comparison.GREATER_THAN, 50L),
                new Predicate("city", Comparison.EQUALS, "Odense")), List.of()));

        assertEquals(new ScanStats(4, 1, 3), plan.scanStats());
        assertEquals(List.of(List.of("Odense", 95L, 120.75)), drain(plan.root()));
    }

    @Test
    void aWhereClauseProducesAFilterOverAScan(@TempDir Path dir) {
        Plan plan = planWhere(dir, "distance", Comparison.GREATER_THAN, 100L);

        assertInstanceOf(FilterOperator.class, plan.root());
    }

    @Test
    void noWhereClauseProducesABareScanOverEveryPartition(@TempDir Path dir) {
        Plan plan = new Planner(copy(dir)).plan(new SelectStatement(List.of("trips"), List.of(), List.of()));

        assertInstanceOf(ScanOperator.class, plan.root());
        assertEquals(new ScanStats(4, 4, 0), plan.scanStats());
        assertEquals(8, drain(plan.root()).size());
    }

    @Test
    void aPlanKeepsReadingTheVersionItPinnedAfterALaterCopy(@TempDir Path dir) {
        StorageEngine engine = copy(dir);
        Planner planner = new Planner(engine);
        Plan pinned = planner.plan(new SelectStatement(List.of("trips"), List.of(), List.of()));

        engine.copyFile("trips", SORTED_CSV);

        assertEquals(1, pinned.scans().get(0).version());
        assertEquals(8, drain(pinned.root()).size(), "the second copy is not visible to this plan");

        Plan fresh = planner.plan(new SelectStatement(List.of("trips"), List.of(), List.of()));
        assertEquals(2, fresh.scans().get(0).version());
        assertEquals(16, drain(fresh.root()).size());
    }

    @Test
    void pruningDecidesOnThePinnedVersion(@TempDir Path dir) {
        StorageEngine engine = copy(dir);
        Plan pinned = new Planner(engine).plan(new SelectStatement(List.of("trips"),
                List.of(new Predicate("distance", Comparison.GREATER_THAN, 100L)), List.of()));

        engine.copyFile("trips", SORTED_CSV);

        assertEquals(new ScanStats(4, 2, 2), pinned.scanStats());
        assertEquals(4, drain(pinned.root()).size());
    }

    @Test
    void rejectsAnUnknownTable(@TempDir Path dir) {
        Planner planner = new Planner(copy(dir));

        assertThrows(IllegalArgumentException.class,
                () -> planner.plan(new SelectStatement(List.of("nope"), List.of(), List.of())));
    }

    @Test
    void rejectsAnUnknownColumn(@TempDir Path dir) {
        Planner planner = new Planner(copy(dir));
        SelectStatement select = new SelectStatement(List.of("trips"),
                List.of(new Predicate("nope", Comparison.EQUALS, 1L)), List.of());

        assertThrows(IllegalArgumentException.class, () -> planner.plan(select));
    }

    @Test
    void fourTablesJoinIntoRowsInFromListOrder(@TempDir Path dir) {
        Plan plan = new Planner(testbed(dir)).plan(new SelectStatement(TESTBED_TABLES, List.of(), TESTBED_JOINS));

        assertInstanceOf(HashJoinOperator.class, plan.root());
        assertEquals(List.of(
                List.of(100L, "Anna", "Bike", 1L, "Anna", "Copenhagen", "Bike", 2999.0,
                        "Copenhagen", "Denmark", 1400000L),
                List.of(101L, "Bob", "Helmet", 2L, "Bob", "Aarhus", "Helmet", 299.0,
                        "Aarhus", "Denmark", 290000L),
                List.of(102L, "Carol", "Bike", 1L, "Carol", "Copenhagen", "Bike", 2999.0,
                        "Copenhagen", "Denmark", 1400000L),
                List.of(103L, "Anna", "Helmet", 1L, "Anna", "Copenhagen", "Helmet", 299.0,
                        "Copenhagen", "Denmark", 1400000L)),
                sorted(drain(plan.root())));
    }

    @Test
    void eachTablePrunesWithItsOwnFiltersBelowTheJoins(@TempDir Path dir) {
        Plan plan = new Planner(testbed(dir)).plan(new SelectStatement(TESTBED_TABLES,
                List.of(new Predicate("o_orderkey", Comparison.GREATER_THAN, 101L)), TESTBED_JOINS));

        assertInstanceOf(HashJoinOperator.class, plan.root());
        assertEquals(List.of(
                new Plan.TableScan("orders", 1, new ScanStats(2, 1, 1)),
                new Plan.TableScan("customers", 1, new ScanStats(2, 2, 0)),
                new Plan.TableScan("products", 1, new ScanStats(1, 1, 0)),
                new Plan.TableScan("cities", 1, new ScanStats(1, 1, 0))),
                plan.scans());
        assertEquals(List.of(102L, 103L), drain(plan.root()).stream().map(row -> row.get(0)).sorted().toList());
    }

    @Test
    void aJoinPredicateMayNameTheNewTableFirst(@TempDir Path dir) {
        Plan plan = new Planner(testbed(dir)).plan(new SelectStatement(List.of("orders", "customers"),
                List.of(), List.of(new JoinPredicate("c_name", "o_custname"))));

        List<List<Object>> rows = sorted(drain(plan.root()));
        assertEquals(4, rows.size());
        assertEquals(List.of(100L, "Anna", "Bike", 1L, "Anna", "Copenhagen"), rows.get(0));
    }

    private static StorageEngine testbed(Path dir) {
        StorageEngine engine = new StorageEngine(dir, 2);
        engine.createTable("orders", List.of(
                new ColumnSpec("o_orderkey", ColumnType.LONG),
                new ColumnSpec("o_custname", ColumnType.STRING),
                new ColumnSpec("o_prodname", ColumnType.STRING),
                new ColumnSpec("o_quantity", ColumnType.LONG)));
        engine.createTable("products", List.of(
                new ColumnSpec("p_name", ColumnType.STRING),
                new ColumnSpec("p_price", ColumnType.DOUBLE)));
        engine.createTable("customers", List.of(
                new ColumnSpec("c_name", ColumnType.STRING),
                new ColumnSpec("c_city", ColumnType.STRING)));
        engine.createTable("cities", List.of(
                new ColumnSpec("ct_city", ColumnType.STRING),
                new ColumnSpec("ct_country", ColumnType.STRING),
                new ColumnSpec("ct_population", ColumnType.LONG)));
        for (String table : List.of("orders", "products", "customers", "cities")) {
            engine.copyFile(table, "src/test/resources/testbed/" + table + ".csv");
        }
        return engine;
    }

    private static List<List<Object>> sorted(List<List<Object>> rows) {
        return rows.stream().sorted(Comparator.comparing(row -> (Long) row.get(0))).toList();
    }

    private static Plan planWhere(Path dir, String column, Comparison comparison, Object constant) {
        return new Planner(copy(dir)).plan(new SelectStatement(List.of("trips"),
                List.of(new Predicate(column, comparison, constant)), List.of()));
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
