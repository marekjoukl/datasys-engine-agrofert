package dk.itu.datasys.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dk.itu.datasys.ColumnSpec;
import dk.itu.datasys.ColumnType;
import dk.itu.datasys.Comparison;

class SqlPrinterTest {

    private static final SqlPrinter PRINTER = new SqlPrinter();

    @Test
    void printsCreateTable() {
        assertEquals("CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);",
                PRINTER.print(new CreateTableStatement("trips", List.of(
                        new ColumnSpec("city", ColumnType.STRING),
                        new ColumnSpec("distance", ColumnType.LONG),
                        new ColumnSpec("price", ColumnType.DOUBLE)))));
    }

    @Test
    void printsCopy() {
        assertEquals("COPY trips FROM 'trips.csv';",
                PRINTER.print(new CopyStatement("trips", "trips.csv")));
    }

    @Test
    void printsSelectWithoutAWhereClause() {
        assertEquals("SELECT * FROM trips;",
                PRINTER.print(new SelectStatement("trips", Optional.empty())));
    }

    @Test
    void printsEveryComparisonSymbol() {
        assertEquals("SELECT * FROM trips WHERE distance = 100;", printed(Comparison.EQUALS, 100L));
        assertEquals("SELECT * FROM trips WHERE distance < 100;",
                printed(Comparison.LESS_THAN, 100L));
        assertEquals("SELECT * FROM trips WHERE distance > 100;",
                printed(Comparison.GREATER_THAN, 100L));
    }

    @Test
    void quotesStringConstantsAndLeavesNumbersBare() {
        assertEquals("SELECT * FROM trips WHERE distance = 'Odense';",
                printed(Comparison.EQUALS, "Odense"));
        assertEquals("SELECT * FROM trips WHERE distance = 42;", printed(Comparison.EQUALS, 42L));
        assertEquals("SELECT * FROM trips WHERE distance = 0.1;", printed(Comparison.EQUALS, 0.1));
    }

    @ParameterizedTest
    @ValueSource(doubles = {
            Double.MIN_VALUE, -Double.MIN_VALUE, Double.MIN_NORMAL,
            Double.MAX_VALUE, -Double.MAX_VALUE,
    })
    void extremeDoublesPrintWithoutExponentNotation(double value) {
        String literal = SqlPrinter.printDouble(value);

        assertEquals(-1, literal.indexOf('E'), literal);
        assertTrue(literal.matches("-?[0-9]+\\.[0-9]+"), literal);
    }

    @ParameterizedTest
    @ValueSource(doubles = {
            0.0, -0.0, 1.0, -1.0, 12.0, 0.1, 1.234567890123456789,
            Double.MIN_VALUE, -Double.MIN_VALUE, Double.MIN_NORMAL,
            Double.MAX_VALUE, -Double.MAX_VALUE,
    })
    void everyDoubleSurvivesThePrinterRoundTrip(double value) {
        SelectStatement original = select(Comparison.EQUALS, value);

        List<Statement> reparsed = new SqlParser().parse(PRINTER.print(original));

        assertEquals(List.of(original), reparsed);
    }

    @Test
    void negativeZeroKeepsItsSign() {
        assertEquals("-0.0", SqlPrinter.printDouble(-0.0));
        assertEquals("0.0", SqlPrinter.printDouble(0.0));
    }

    private static String printed(Comparison comparison, Object constant) {
        return PRINTER.print(select(comparison, constant));
    }

    private static SelectStatement select(Comparison comparison, Object constant) {
        return new SelectStatement("trips",
                Optional.of(new Predicate("distance", comparison, constant)));
    }
}
