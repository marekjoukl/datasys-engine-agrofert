package dk.itu.datasys;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.datasys.sql.SqlParser;
import dk.itu.datasys.sql.SqlPrinter;
import dk.itu.datasys.sql.Statement;

public final class Engine {

    private static final Logger LOGGER = LoggerFactory.getLogger(Engine.class);

    static final String EXAMPLE_SCRIPT = """
            CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
            COPY trips FROM 'trips.csv';
            SELECT * FROM trips WHERE distance > 100;
            SELECT * FROM trips;              -- WHERE is optional, as in DuckDB
            """;

    public static void main(String[] args) {
        LoggingContext.initialise();

        LOGGER.debug("engine started");
        printExampleScript();
        LOGGER.debug("engine stopped");
    }

    static void printExampleScript() {
        List<Statement> statements = new SqlParser().parse(EXAMPLE_SCRIPT);

        SqlPrinter printer = new SqlPrinter();
        for (Statement statement : statements) {
            System.out.println(printer.print(statement));
        }
    }

    private Engine() {
    }
}
