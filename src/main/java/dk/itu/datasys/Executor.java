package dk.itu.datasys;

import static dk.itu.datasys.StorageEngine.field;
import static dk.itu.datasys.StorageEngine.millisSince;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import dk.itu.datasys.sql.Binder;
import dk.itu.datasys.sql.CopyStatement;
import dk.itu.datasys.sql.CreateTableStatement;
import dk.itu.datasys.sql.SelectStatement;
import dk.itu.datasys.sql.SqlParser;
import dk.itu.datasys.sql.SqlPrinter;
import dk.itu.datasys.sql.Statement;

public final class Executor {

    private static final Logger LOGGER = LoggerFactory.getLogger(Executor.class);

    private final StorageEngine engine;
    private final Binder binder;
    private final Planner planner;
    private final SqlPrinter printer = new SqlPrinter();
    private final PrintStream out;

    private int statementNumber;

    public Executor(StorageEngine engine, PrintStream out) {
        this.engine = engine;
        this.binder = new Binder(engine);
        this.planner = new Planner(engine);
        this.out = out;
    }

    public void run(String sqlText) {
        LoggingContext.initialise();
        List<Statement> statements = new SqlParser().parse(sqlText);

        try {
            for (Statement statement : statements) {
                statementNumber++;
                MDC.put("statementNumber", String.valueOf(statementNumber));
                execute(statement);
            }
        } finally {
            MDC.put("statementNumber", "0");
        }
    }

    private void execute(Statement statement) {
        long startedAt = System.nanoTime();
        LOGGER.debug("sql={}", field(printer.print(statement)));

        try {
            binder.bind(statement);

            switch (statement) {
                case CreateTableStatement create -> {
                    engine.createTable(create.tableName(), create.columns());
                    LOGGER.debug("statement=CREATE_TABLE table={} columns={} durationMs={}",
                            field(create.tableName()), create.columns().size(),
                            millisSince(startedAt));
                }
                case CopyStatement copy -> {
                    engine.copyFile(copy.tableName(), copy.csvFilePath());
                    LOGGER.debug("statement=COPY table={} partitions={} durationMs={}",
                            field(copy.tableName()), engine.partitionCount(copy.tableName()),
                            millisSince(startedAt));
                }
                case SelectStatement select -> {
                    Plan plan = planner.plan(select);
                    int rowsOut = print(plan.root());

                    LOGGER.debug("statement=SELECT table={} rowsOut={} partitionsRead={}"
                            + " partitionsPruned={} durationMs={}",
                            field(select.tableName()), rowsOut,
                            plan.scanStats().partitionsRead(), plan.scanStats().partitionsPruned(),
                            millisSince(startedAt));
                }
            }
        } catch (RuntimeException e) {
            LOGGER.error("statement failed type={} error={} message={} durationMs={}",
                    statement.getClass().getSimpleName(), e.getClass().getSimpleName(),
                    field(e.getMessage()), millisSince(startedAt));
            throw e;
        }
    }

    private int print(Operator root) {
        int rowsOut = 0;
        root.open();
        try {
            for (Object[] row = root.next(); row != null; row = root.next()) {
                out.println(toCsv(row));
                rowsOut++;
            }
        } finally {
            root.close();
        }
        return rowsOut;
    }

    private static String toCsv(Object[] row) {
        return Arrays.stream(row).map(Executor::csvValue).collect(Collectors.joining(","));
    }

    private static String csvValue(Object value) {
        if (value instanceof Double number) {
            return SqlPrinter.printDouble(number);
        }
        return String.valueOf(value);
    }
}
