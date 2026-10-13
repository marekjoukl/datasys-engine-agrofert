package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import dk.itu.datasys.sql.Predicate;

class FilterOperatorTest {

    private static final List<ColumnSpec> TRIPS = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    private static final List<Object[]> ROWS = List.of(
            new Object[] {"Copenhagen", 12L, 23.5},
            new Object[] {"Odense", 95L, 120.75},
            new Object[] {"Aarhus", 187L, 301.0});

    @Test
    void emitsOnlyTheRowsThatPass() {
        assertEquals(List.of(
                List.of("Odense", 95L, 120.75),
                List.of("Aarhus", 187L, 301.0)),
                drain(filterOn("distance", Comparison.GREATER_THAN, 50L)));
    }

    @Test
    void emitsNothingWhenNoRowPasses() {
        assertEquals(List.of(), drain(filterOn("distance", Comparison.GREATER_THAN, 1000L)));
    }

    @Test
    void emitsEveryRowWhenEveryRowPasses() {
        assertEquals(asLists(ROWS), drain(filterOn("distance", Comparison.GREATER_THAN, -1L)));
    }

    @Test
    void emitsNothingForAnEmptyChild() {
        FilterOperator filter = new FilterOperator(
                new TestListOperator(List.of()),
                ColumnPredicate.of(TRIPS, new Predicate("distance", Comparison.GREATER_THAN, 0L)));

        filter.open();

        assertNull(filter.next());
        filter.close();
    }

    @Test
    void comparesTheColumnThatTheWhereClauseNamed() {
        assertEquals(List.of(List.of("Copenhagen", 12L, 23.5)),
                drain(filterOn("price", Comparison.LESS_THAN, 100.0)));
    }

    @Test
    void countsEveryRowItSawAndEveryRowItPassed() {
        FilterOperator filter = filterOn("city", Comparison.EQUALS, "Aarhus");

        drain(filter);

        assertEquals(3, filter.rowsIn);
        assertEquals(1, filter.rowsOut);
    }

    @Test
    void opensAndClosesItsChild() {
        TestListOperator child = new TestListOperator(ROWS);
        FilterOperator filter = new FilterOperator(child,
                ColumnPredicate.of(TRIPS, new Predicate("distance", Comparison.GREATER_THAN, 0L)));

        filter.open();
        filter.close();

        assertEquals(1, child.openCount());
        assertEquals(1, child.closeCount());
    }

    @Test
    void canBeDrainedTwiceBecauseOpenResets() {
        FilterOperator filter = filterOn("distance", Comparison.GREATER_THAN, 50L);

        List<List<Object>> first = drain(filter);

        assertEquals(2, first.size());
        assertEquals(first, drain(filter));
    }

    private static FilterOperator filterOn(String column, Comparison comparison, Object constant) {
        return new FilterOperator(
                new TestListOperator(ROWS),
                ColumnPredicate.of(TRIPS, new Predicate(column, comparison, constant)));
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
        return asLists(rows);
    }

    private static List<List<Object>> asLists(List<Object[]> rows) {
        return rows.stream().map(Arrays::asList).toList();
    }
}
