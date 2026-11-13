package dk.itu.datasys.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import dk.itu.datasys.ColumnSpec;
import dk.itu.datasys.ColumnType;
import dk.itu.datasys.Comparison;

class SqlParserTest {

    private static List<Statement> parse(String sql) {
        return new SqlParser().parse(sql);
    }

    private static Statement one(String sql) {
        List<Statement> statements = parse(sql);
        assertEquals(1, statements.size(), sql);
        return statements.get(0);
    }

    private static Object constantOf(String sql) {
        return ((SelectStatement) one(sql)).filters().get(0).constant();
    }

    private static SqlParseException error(String sql) {
        return assertThrows(SqlParseException.class, () -> parse(sql), sql);
    }

    // 1. Statement shapes

    @Test
    void createTableParsesToTheExpectedAst() {
        assertEquals(
                new CreateTableStatement("trips", List.of(
                        new ColumnSpec("city", ColumnType.STRING),
                        new ColumnSpec("distance", ColumnType.LONG),
                        new ColumnSpec("price", ColumnType.DOUBLE))),
                one("CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);"));
    }

    @Test
    void createTableAcceptsASingleColumn() {
        assertEquals(
                new CreateTableStatement("t", List.of(new ColumnSpec("a", ColumnType.LONG))),
                one("CREATE TABLE t (a LONG);"));
    }

    @Test
    void copyParsesToTheExpectedAst() {
        assertEquals(
                new CopyStatement("trips", "trips.csv"),
                one("COPY trips FROM 'trips.csv';"));
    }

    @Test
    void selectWithoutWhereParsesToTheExpectedAst() {
        assertEquals(
                new SelectStatement(List.of("trips"), List.of(), List.of()),
                one("SELECT * FROM trips;"));
    }

    @Test
    void selectWithWhereParsesToTheExpectedAst() {
        assertEquals(
                new SelectStatement(List.of("trips"), List.of(
                        new Predicate("distance", Comparison.GREATER_THAN, 100L)), List.of()),
                one("SELECT * FROM trips WHERE distance > 100;"));
    }

    @Test
    void aTableListKeepsItsOrder() {
        assertEquals(
                new SelectStatement(List.of("orders", "customers", "products"), List.of(), List.of()),
                one("SELECT * FROM orders, customers, products;"));
    }

    @Test
    void columnToColumnIsAJoinAndColumnToConstantIsAFilter() {
        assertEquals(
                new SelectStatement(List.of("orders", "customers"),
                        List.of(new Predicate("c_city", Comparison.EQUALS, "Copenhagen")),
                        List.of(new JoinPredicate("o_custname", "c_name"))),
                one("SELECT * FROM orders, customers"
                        + " WHERE o_custname = c_name AND c_city = 'Copenhagen';"));
    }

    @Test
    void anAndChainKeepsTheOrderWithinEachList() {
        SelectStatement select = (SelectStatement) one("""
                SELECT * FROM orders, customers, products, cities
                WHERE o_custname = c_name AND o_quantity > 1 AND o_prodname = p_name
                AND c_city = ct_city AND p_price < 1000.0;""");

        assertEquals(List.of(
                new Predicate("o_quantity", Comparison.GREATER_THAN, 1L),
                new Predicate("p_price", Comparison.LESS_THAN, 1000.0)), select.filters());
        assertEquals(List.of(
                new JoinPredicate("o_custname", "c_name"),
                new JoinPredicate("o_prodname", "p_name"),
                new JoinPredicate("c_city", "ct_city")), select.joins());
    }

    @Test
    void deleteWithoutWhereParsesToTheExpectedAst() {
        assertEquals(
                new DeleteStatement("trips", Optional.empty()),
                one("DELETE FROM trips;"));
    }

    @Test
    void deleteWithWhereParsesToTheExpectedAst() {
        assertEquals(
                new DeleteStatement("trips", Optional.of(
                        new Predicate("city", Comparison.EQUALS, "Aarhus"))),
                one("DELETE FROM trips WHERE city = 'Aarhus';"));
    }

    @Test
    void everyComparisonOperatorIsMapped() {
        assertEquals(Comparison.EQUALS, comparisonOf("SELECT * FROM t WHERE a = 1;"));
        assertEquals(Comparison.LESS_THAN, comparisonOf("SELECT * FROM t WHERE a < 1;"));
        assertEquals(Comparison.GREATER_THAN, comparisonOf("SELECT * FROM t WHERE a > 1;"));
    }

    @Test
    void aScriptReturnsEveryStatementInOrder() {
        List<Statement> statements = parse("""
                CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
                COPY trips FROM 'trips.csv';
                SELECT * FROM trips WHERE distance > 100;
                DELETE FROM trips WHERE city = 'Aarhus';
                SELECT * FROM trips;
                """);

        assertEquals(5, statements.size());
        assertEquals(
                List.of(CreateTableStatement.class, CopyStatement.class,
                        SelectStatement.class, DeleteStatement.class, SelectStatement.class),
                statements.stream().map(Object::getClass).toList());
    }

    // 2. Literal typing

    static Stream<Arguments> literals() {
        return Stream.of(
                arguments("12", 12L),
                arguments("12.0", 12.0),
                arguments("'12'", "12"),
                arguments("9223372036854775807", Long.MAX_VALUE),
                arguments("-9223372036854775808", Long.MIN_VALUE));
    }

    @ParameterizedTest
    @MethodSource("literals")
    void literalTextBecomesTheRightJavaType(String literal, Object expected) {
        assertEquals(expected, constantOf("SELECT * FROM t WHERE x = " + literal + ";"));
    }

    @Test
    void theSignIsPartOfANumericLiteral() {
        assertEquals(-1L, constantOf("SELECT * FROM t WHERE x = -1;"));
        assertEquals(-1.5, constantOf("SELECT * FROM t WHERE x = -1.5;"));
    }

    @Test
    void aCopyPathKeepsItsContentButNotItsQuotes() {
        assertEquals(
                new CopyStatement("t", "data/trips-2026.csv"),
                one("COPY t FROM 'data/trips-2026.csv';"));
    }

    // 3. Case handling

    @Test
    void keywordsAreCaseInsensitive() {
        assertEquals(
                new SelectStatement(List.of("trips"), List.of(), List.of()),
                one("select * from trips;"));
        assertEquals(
                new SelectStatement(List.of("trips"), List.of(), List.of()),
                one("SeLeCt * FrOm trips;"));
    }

    @Test
    void identifiersKeepTheCaseTheyWereWrittenIn() {
        SelectStatement select = (SelectStatement) one("SELECT * FROM Trips WHERE City = 'x';");

        assertEquals(List.of("Trips"), select.tableNames());
        assertEquals("City", select.filters().get(0).columnName());
    }

    @Test
    void columnTypeKeywordsAreCaseInsensitive() {
        assertEquals(
                new CreateTableStatement("t", List.of(
                        new ColumnSpec("a", ColumnType.STRING),
                        new ColumnSpec("b", ColumnType.DOUBLE))),
                one("create table t (a string, b Double);"));
    }

    // 4. Malformed input reports line and column

    static Stream<Arguments> malformedStatements() {
        return Stream.of(
                arguments("SELECT * FROM trips", 19),
                arguments("CREATE TABLE t (a STRING;", 24),
                arguments("CREATE TABLE t (city TEXT);", 21),
                arguments("SELECT * FROM t WHERE c = 'oops;", 26),
                arguments("SELECT * trips;", 9),
                arguments("SELECT * FROM trips; garbage", 21),
                arguments("DELETE FROM trips WHERE a = b;", 28),
                arguments("DELETE FROM a, b;", 13),
                arguments("SELECT * FROM a, b WHERE x = y OR z = 1;", 31),
                arguments("SELECT * FROM a, b WHERE x = y AND;", 34),
                // whole numbers just outside the LONG range
                arguments("SELECT * FROM t WHERE x = 9223372036854775808;", 26),
                arguments("SELECT * FROM t WHERE x = -9223372036854775809;", 26));
    }

    @ParameterizedTest
    @MethodSource("malformedStatements")
    void malformedInputIsRejectedAtTheRightColumn(String sql, int column) {
        SqlParseException e = error(sql);

        assertEquals(1, e.line());
        assertEquals(column, e.column());
    }

    @Test
    void theReportedLineIsTheLineTheErrorIsOn() {
        SqlParseException e = error("""
                SELECT * FROM trips;
                SELECT * trips;
                """);

        assertEquals(2, e.line());
        assertEquals(9, e.column());
    }

    // 5. Comments and whitespace

    @Test
    void commentsAndWhitespaceAreSkipped() {
        List<Statement> commented = parse("""
                -- load the trips table
                CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);

                    SELECT *
                      FROM trips
                     WHERE distance > 100;   -- only the long ones
                """);

        assertEquals(parse(
                "CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);"
                + "SELECT * FROM trips WHERE distance > 100;"),
                commented);
    }

    @Test
    void aCommentCanBeTheOnlyThingOnALine() {
        assertEquals(
                new SelectStatement(List.of("t"), List.of(), List.of()),
                one("-- a comment\nSELECT * FROM t;\n-- another\n"));
    }

    private static Comparison comparisonOf(String sql) {
        return ((SelectStatement) one(sql)).filters().get(0).comparison();
    }
}
