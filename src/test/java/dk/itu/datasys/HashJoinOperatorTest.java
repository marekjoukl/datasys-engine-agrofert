package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class HashJoinOperatorTest {

    private static final List<Object[]> ORDERS = List.of(
            new Object[] {100L, "Anna", "Bike", 1L},
            new Object[] {101L, "Bob", "Helmet", 2L},
            new Object[] {102L, "Carol", "Bike", 1L},
            new Object[] {103L, "Anna", "Helmet", 1L});

    private static final List<Object[]> CUSTOMERS = List.of(
            new Object[] {"Anna", "Copenhagen"},
            new Object[] {"Bob", "Aarhus"},
            new Object[] {"Carol", "Copenhagen"});

    private static HashJoinOperator join(List<Object[]> build, List<Object[]> probe,
                                         int buildKey, int probeKey) {
        return new HashJoinOperator(new TestListOperator(build), new TestListOperator(probe),
                buildKey, probeKey, "build;probe");
    }

    private static List<List<Object>> drain(Operator operator) {
        List<List<Object>> rows = new ArrayList<>();
        operator.open();
        for (Object[] row = operator.next(); row != null; row = operator.next()) {
            rows.add(List.of(row));
        }
        operator.close();
        return rows;
    }

    @Test
    void everyOrderFindsItsCustomer() {
        List<List<Object>> rows = drain(join(ORDERS, CUSTOMERS, 1, 0));

        assertEquals(List.of(
                List.of(100L, "Anna", "Bike", 1L, "Anna", "Copenhagen"),
                List.of(103L, "Anna", "Helmet", 1L, "Anna", "Copenhagen"),
                List.of(101L, "Bob", "Helmet", 2L, "Bob", "Aarhus"),
                List.of(102L, "Carol", "Bike", 1L, "Carol", "Copenhagen")), rows);
    }

    @Test
    void duplicatesOnBothSidesMultiply() {
        List<Object[]> build = List.of(new Object[] {"x", 1L}, new Object[] {"x", 2L});
        List<Object[]> probe = List.of(
                new Object[] {"x", "a"}, new Object[] {"x", "b"}, new Object[] {"x", "c"});

        List<List<Object>> rows = drain(join(build, probe, 0, 0));

        assertEquals(6, rows.size());
        assertEquals(List.of(
                List.of("x", 1L, "x", "a"), List.of("x", 2L, "x", "a"),
                List.of("x", 1L, "x", "b"), List.of("x", 2L, "x", "b"),
                List.of("x", 1L, "x", "c"), List.of("x", 2L, "x", "c")), rows);
    }

    @Test
    void rowsWithoutAPartnerAreDropped() {
        List<Object[]> build = List.of(new Object[] {"a"}, new Object[] {"b"});
        List<Object[]> probe = List.of(new Object[] {"b"}, new Object[] {"c"});

        assertEquals(List.of(List.of("b", "b")), drain(join(build, probe, 0, 0)));
    }

    @Test
    void anEmptyBuildSideReturnsNoRows() {
        assertEquals(List.of(), drain(join(List.of(), CUSTOMERS, 1, 0)));
    }

    @Test
    void anEmptyProbeSideReturnsNoRows() {
        assertEquals(List.of(), drain(join(ORDERS, List.of(), 1, 0)));
    }

    @Test
    void keysOfDifferentTypesDoNotMatch() {
        List<Object[]> build = List.<Object[]>of(new Object[] {3L});
        List<Object[]> probe = List.<Object[]>of(new Object[] {3.0});

        assertEquals(List.of(), drain(join(build, probe, 0, 0)));
    }

    @Test
    void theOperatorCanBeOpenedAgain() {
        HashJoinOperator join = join(ORDERS, CUSTOMERS, 1, 0);

        assertEquals(4, drain(join).size());
        assertEquals(4, drain(join).size());
    }

    @Test
    void bothChildrenAreOpenedAndClosedOnce() {
        TestListOperator build = new TestListOperator(ORDERS);
        TestListOperator probe = new TestListOperator(CUSTOMERS);

        drain(new HashJoinOperator(build, probe, 1, 0, "orders;customers"));

        assertEquals(1, build.openCount());
        assertEquals(1, build.closeCount());
        assertEquals(1, probe.openCount());
        assertEquals(1, probe.closeCount());
    }
}
