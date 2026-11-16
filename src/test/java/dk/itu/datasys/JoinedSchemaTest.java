package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class JoinedSchemaTest {

    private static final List<ColumnSpec> ORDERS = List.of(
            new ColumnSpec("o_orderkey", ColumnType.LONG),
            new ColumnSpec("o_custname", ColumnType.STRING),
            new ColumnSpec("o_prodname", ColumnType.STRING),
            new ColumnSpec("o_quantity", ColumnType.LONG));

    private static final List<ColumnSpec> CUSTOMERS = List.of(
            new ColumnSpec("c_name", ColumnType.STRING),
            new ColumnSpec("c_city", ColumnType.STRING));

    private static JoinedSchema ordersAndCustomers() {
        return new JoinedSchema(List.of("orders", "customers"),
                Map.of("orders", ORDERS, "customers", CUSTOMERS)::get);
    }

    @Test
    void aColumnOfTheSecondTableIsShiftedByTheFirstTablesWidth() {
        JoinedSchema.Column column = ordersAndCustomers().resolve("c_city");

        assertEquals("customers", column.table());
        assertEquals(5, column.index());
        assertEquals(1, column.localIndex());
    }

    @Test
    void aColumnOfTheFirstTableHasNoOffset() {
        JoinedSchema.Column column = ordersAndCustomers().resolve("o_orderkey");

        assertEquals("orders", column.table());
        assertEquals(0, column.localIndex());
        assertEquals(0, column.index());
    }

    @Test
    void theOrderOfTheFromListDecidesTheOffsets() {
        JoinedSchema schema = new JoinedSchema(List.of("customers", "orders"),
                Map.of("orders", ORDERS, "customers", CUSTOMERS)::get);

        assertEquals(2, schema.resolve("o_orderkey").index());
        assertEquals(1, schema.resolve("c_city").index());
    }

    @Test
    void aColumnInNoTableIsUnknown() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ordersAndCustomers().resolve("nope"));

        assertTrue(e.getMessage().contains("unknown column: nope"), e.getMessage());
    }

    @Test
    void aColumnInTwoTablesIsAmbiguous() {
        List<ColumnSpec> withId = List.of(new ColumnSpec("id", ColumnType.LONG));
        JoinedSchema schema = new JoinedSchema(List.of("t1", "t2"),
                Map.of("t1", withId, "t2", withId)::get);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> schema.resolve("id"));

        assertTrue(e.getMessage().contains("ambiguous column: id"), e.getMessage());
    }

    @Test
    void aTableListedTwiceIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new JoinedSchema(List.of("orders", "orders"), Map.of("orders", ORDERS)::get));

        assertTrue(e.getMessage().contains("a table may be listed once: orders"), e.getMessage());
    }
}
