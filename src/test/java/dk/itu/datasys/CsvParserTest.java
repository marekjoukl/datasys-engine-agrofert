package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class CsvParserTest {

    private static final List<ColumnSpec> TRIPS = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    @Test
    void wellFormedLineParsesByPosition() {
        Object[] row = CsvParser.parseLine(TRIPS, "Copenhagen,12,23.5", "trips.csv", 1);

        assertEquals(3, row.length);
        assertEquals("Copenhagen", row[0]);
        assertEquals(12L, row[1]);
        assertEquals(23.5, row[2]);
    }

    @Test
    void negativeAndFractionalValuesParse() {
        Object[] row = CsvParser.parseLine(TRIPS, "Esbjerg,-299,450.25", "trips.csv", 8);

        assertEquals(-299L, row[1]);
        assertEquals(450.25, row[2]);
    }

    @Test
    void malformedLongIsRejectedWithFileAndLine() {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> CsvParser.parseLine(TRIPS, "Copenhagen,abc,23.5", "trips.csv", 4));

        assertTrue(e.getMessage().contains("trips.csv"), e.getMessage());
        assertTrue(e.getMessage().contains("4"), e.getMessage());
        assertTrue(e.getMessage().contains("distance"), e.getMessage());
    }

    @Test
    void malformedDoubleIsRejectedWithFileAndLine() {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> CsvParser.parseLine(TRIPS, "Copenhagen,12,not-a-price", "trips.csv", 7));

        assertTrue(e.getMessage().contains("trips.csv"), e.getMessage());
        assertTrue(e.getMessage().contains("7"), e.getMessage());
    }

    @Test
    void tooFewFieldsIsRejectedWithFileAndLine() {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> CsvParser.parseLine(TRIPS, "Copenhagen,12", "trips.csv", 2));

        assertTrue(e.getMessage().contains("trips.csv"), e.getMessage());
        assertTrue(e.getMessage().contains("2"), e.getMessage());
    }

    @Test
    void tooManyFieldsIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CsvParser.parseLine(TRIPS, "Copenhagen,12,23.5,extra", "trips.csv", 3));
    }

    @Test
    void trailingEmptyStringFieldIsKept() {
        List<ColumnSpec> schema = List.of(
                new ColumnSpec("distance", ColumnType.LONG),
                new ColumnSpec("city", ColumnType.STRING));

        Object[] row = CsvParser.parseLine(schema, "12,", "trips.csv", 1);

        assertEquals(12L, row[0]);
        assertEquals("", row[1]);
    }
}
