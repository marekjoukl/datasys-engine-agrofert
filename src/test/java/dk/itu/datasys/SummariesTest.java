package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void parsesLongSummaries() {
        MinMax res = Summaries.parse(ColumnType.LONG, "12", "187");
        assertEquals(12L, res.min());
        assertEquals(187L, res.max());
    }

    @Test
    void parsesDoubleSummaries() {
        MinMax res = Summaries.parse(ColumnType.DOUBLE, "23.5", "450.25");
        assertEquals(23.5, res.min());
        assertEquals(450.25, res.max());
    }

    @Test
    void parsesStringSummaries() {
        MinMax res = Summaries.parse(ColumnType.STRING, "Aalborg", "Roskilde");
        assertEquals("Aalborg", res.min());
        assertEquals("Roskilde", res.max());
    }

    @Test
    void computedLongSummariesSurviveTheCatalogTextRoundTrip() {
        MinMax computed = Summaries.compute(ColumnType.LONG, List.of(-5L, 3L, -20L));

        assertEquals(computed, roundTrip(ColumnType.LONG, computed));
    }

    @Test
    void computedDoubleSummariesSurviveTheCatalogTextRoundTrip() {
        MinMax computed = Summaries.compute(ColumnType.DOUBLE, List.of(99.99, 450.25, 23.5));

        assertEquals(computed, roundTrip(ColumnType.DOUBLE, computed));
    }

    @Test
    void computedStringSummariesSurviveTheCatalogTextRoundTrip() {
        MinMax computed = Summaries.compute(
                ColumnType.STRING, List.of("Copenhagen", "Aalborg", "Odense"));

        assertEquals(computed, roundTrip(ColumnType.STRING, computed));
    }

    @Test
    void parsedSummariesCompareAgainstATypedConstant() {
        MinMax summary = Summaries.parse(ColumnType.LONG, "12", "31");

        assertFalse(Pruner.shouldRead(Comparison.GREATER_THAN, 100L, summary));
        assertTrue(Pruner.shouldRead(Comparison.LESS_THAN, 100L, summary));
    }

    private static MinMax roundTrip(ColumnType type, MinMax summary) {
        return Summaries.parse(
                type, String.valueOf(summary.min()), String.valueOf(summary.max()));
    }
}
