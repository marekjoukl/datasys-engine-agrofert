package dk.itu.datasys;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public final class Engine {

    private static final Logger LOGGER = LoggerFactory.getLogger(Engine.class);

    private static final String GOLDEN_CSV = "src/test/resources/trips.csv";

    private static final List<ColumnSpec> TRIPS = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    public static void main(String[] args) {
        MDC.put("sessionId", UUID.randomUUID().toString());
        MDC.put("statementNumber", "0");

        LOGGER.debug("engine started");
        runGoldenExample();
        LOGGER.debug("engine stopped");
    }

    static void runGoldenExample() {
        Path dataDirectory;
        try {
            dataDirectory = Files.createTempDirectory("agrofert-demo");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        StorageEngine engine = new StorageEngine(dataDirectory, 2);
        engine.createTable("trips", TRIPS);
        engine.copyFile("trips", GOLDEN_CSV);

        print(engine, "distance GREATER_THAN 100",
                engine.select("trips", "distance", Comparison.GREATER_THAN, 100L));
        print(engine, "city EQUALS 'Copenhagen'",
                engine.select("trips", "city", Comparison.EQUALS, "Copenhagen"));
        print(engine, "price LESS_THAN 50.0",
                engine.select("trips", "price", Comparison.LESS_THAN, 50.0));
    }

    private static void print(StorageEngine engine, String predicate, List<Object[]> rows) {
        ScanStats stats = engine.lastScanStats();

        System.out.println(predicate);
        for (Object[] row : rows) {
            System.out.println("  " + Arrays.toString(row));
        }
        System.out.println("  " + rows.size() + " rows, "
                + stats.partitionsRead() + " of " + stats.partitionsTotal()
                + " partitions read, " + stats.partitionsPruned() + " pruned");
        System.out.println();
    }

    private Engine() {
    }
}
