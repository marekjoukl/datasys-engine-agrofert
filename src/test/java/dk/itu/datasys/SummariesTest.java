package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class SummariesTest {

    @Test
    void singleValue() {
        MinMax res = Summaries.compute(ColumnType.LONG, List.of(42L));
        assertEquals(42L, res.min());
        assertEquals(42L, res.max());
    }

    @Test
    void negatives() {
        MinMax res = Summaries.compute(ColumnType.LONG, List.of(-5L, 3L, -20L));
        assertEquals(-20L, res.min());
        assertEquals(3L, res.max());
    }

    @Test
    void strings() {
        MinMax res = Summaries.compute(
                ColumnType.STRING, List.of("Copenhagen", "Aalborg", "Odense"));
        assertEquals("Aalborg", res.min());
        assertEquals("Odense", res.max());
    }

    @Test
    void doubles() {
        MinMax res = Summaries.compute(ColumnType.DOUBLE, List.of(23.5, 450.25, 99.99));
        assertEquals(23.5, res.min());
        assertEquals(450.25, res.max());
    }

    @Test
    void reverseOrder() {
        MinMax res = Summaries.compute(ColumnType.LONG, List.of(9L, 5L, 1L));
        assertEquals(1L, res.min());
        assertEquals(9L, res.max());
    }

    @Test
    void emptyListIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Summaries.compute(ColumnType.LONG, List.of()));
    }
}
