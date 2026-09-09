package dk.itu.datasys;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public final class StorageEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(StorageEngine.class);

    private static final int DEFAULT_MAX_ROWS_PER_PARTITION = 1024;

    private final Path dataDirectory;
    private final int maxRowsPerPartition;
    private ScanStats lastScanStats;

    public StorageEngine(Path dataDirectory) {
        this(dataDirectory, DEFAULT_MAX_ROWS_PER_PARTITION);
    }

    public StorageEngine(Path dataDirectory, int maxRowsPerPartition) {
        if (maxRowsPerPartition <= 0) {
            throw new IllegalArgumentException(
                    "maxRowsPerPartition must be positive but was " + maxRowsPerPartition);
        }
        this.dataDirectory = dataDirectory;
        this.maxRowsPerPartition = maxRowsPerPartition;
        initialiseLoggingContext();
    }

    private static void initialiseLoggingContext() {
        if (MDC.get("sessionId") == null) {
            MDC.put("sessionId", UUID.randomUUID().toString());
        }
        if (MDC.get("statementNumber") == null) {
            MDC.put("statementNumber", "0");
        }
    }

    private Path tableDirectory(String tableName) {
        return dataDirectory.resolve(tableName);
    }

    private Path catalogFile(String tableName) {
        return tableDirectory(tableName).resolve("catalog.json");
    }

    private TableCatalog requireCatalog(String tableName) {
        if (!Files.exists(catalogFile(tableName))) {
            throw new IllegalArgumentException("unknown table: " + tableName);
        }
        return TableCatalog.load(catalogFile(tableName));
    }

    public void createTable(String tableName, List<ColumnSpec> columns) {
        long startedAt = System.nanoTime();

        if (columns.isEmpty()) {
            throw new IllegalArgumentException("table " + tableName + " needs at least one column");
        }

        Set<String> seen = new HashSet<>();
        for (ColumnSpec column : columns) {
            if (!seen.add(column.name())) {
                throw new IllegalArgumentException("duplicate column name: " + column.name());
            }
        }

        if (Files.exists(catalogFile(tableName))) {
            throw new IllegalArgumentException("table already exists: " + tableName);
        }
        try {
            Files.createDirectories(tableDirectory(tableName));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        TableCatalog.save(new TableCatalog(tableName, columns,false, List.of()), catalogFile(tableName));

        LOGGER.debug("table={} columns={} durationMs={}",
                field(tableName), columns.size(), millisSince(startedAt));
    }

    public void copyFile(String tableName, String csvFilePath) {
        long startedAt = System.nanoTime();
        TableCatalog catalog = requireCatalog(tableName);

        if (catalog.copied()) {
            throw new UnsupportedOperationException(
                    "table " + tableName + " already has data");
        }

        List<Object[]> rows = readCsv(csvFilePath, catalog.columns());
        List<PartitionMeta> partitions = writeDataFile(tableName, catalog.columns(), rows);

        TableCatalog.save(
                new TableCatalog(tableName, catalog.columns(), true, partitions),
                catalogFile(tableName));

        LOGGER.debug("table={} file={} rows={} partitions={} durationMs={}",
                field(tableName), field(Path.of(csvFilePath).getFileName()),
                rows.size(), partitions.size(), millisSince(startedAt));
    }

    private static List<Object[]> readCsv (String csvFilePath, List<ColumnSpec> columns) {
        List<Object[]> rows = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(Path.of(csvFilePath))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                rows.add(CsvParser.parseLine(columns, line, csvFilePath, lineNumber));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return rows;
    }

    private List<PartitionMeta> writeDataFile(String tableName, List<ColumnSpec> columns, List<Object[]> rows) {
        int totalSize = DataFile.HEADER_SIZE;
        for (Object[] row : rows) {
            for (int c = 0; c < columns.size(); c++) {
                totalSize += ValueCodec.encodedSize(columns.get(c).type(), row[c]);
            }
        }

        ByteBuffer buffer = ValueCodec.allocate(totalSize);
        DataFile.writeHeader(buffer);

        List<PartitionMeta> partitions = new ArrayList<>();
        String dataFileName = tableName + ".data";
        int partitionIndex = 0;

        for (int start = 0; start < rows.size(); start += maxRowsPerPartition) {
            int end = Math.min(start + maxRowsPerPartition, rows.size());
            List<Object[]> partitionRows = rows.subList(start, end);

            Map<String, ChunkMeta> chunks = new LinkedHashMap<>();

            for (int c = 0; c < columns.size(); c++) {
                ColumnSpec column = columns.get(c);

                List<Object> values = new ArrayList<>();
                for (Object[] row : partitionRows) {
                    values.add(row[c]);
                }

                long offset = buffer.position();
                for (Object value : values) {
                    ValueCodec.write(buffer, column.type(), value);
                }
                int length = buffer.position() - (int) offset;

                MinMax minMax = Summaries.compute(column.type(), values);
                chunks.put(column.name(), new ChunkMeta(
                        offset, length,
                        String.valueOf(minMax.min()),
                        String.valueOf(minMax.max())));

                LOGGER.debug("table={} partition={} column={} min={} max={}",
                        field(tableName), partitionIndex, field(column.name()),
                        field(minMax.min()), field(minMax.max()));
            }

            partitions.add(new PartitionMeta(dataFileName, partitionRows.size(), chunks));
            partitionIndex++;
        }

        buffer.flip();
        try (FileChannel channel = FileChannel.open(
                tableDirectory(tableName).resolve(dataFileName),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return partitions;
    }

    public List<Object[]> select(String tableName, String columnName,
                                 Comparison comparison, Object constant) {
        TableCatalog catalog = requireCatalog(tableName);
        List<ColumnSpec> columns = catalog.columns();
        int columnIndex = columnIndex(columns, columnName);
        ColumnSpec column = columns.get(columnIndex);
        if (comparison == null) {
            throw new IllegalArgumentException("comparison is required");
        }
        requireMatchingConstant(column, constant);

        long startedAt = System.nanoTime();
        List<PartitionMeta> partitions = catalog.partitions();
        List<Object[]> rows = new ArrayList<>();
        Map<String, DataFile> openFiles = new LinkedHashMap<>();
        int partitionsRead = 0;
        int partitionsPruned = 0;

        try {
            for (int p = 0; p < partitions.size(); p++) {
                PartitionMeta partition = partitions.get(p);
                ChunkMeta chunk = partition.chunks().get(columnName);
                MinMax summary = Summaries.parse(column.type(), chunk.min(), chunk.max());
                boolean read = Pruner.shouldRead(comparison, constant, summary);

                LOGGER.debug(
                        "table={} column={} comparison={} const={} partition={} min={} max={} decision={}",
                        field(tableName), field(columnName), comparison, field(constant), p,
                        field(summary.min()), field(summary.max()), read ? "READ" : "PRUNED");

                if (!read) {
                    partitionsPruned++;
                    continue;
                }

                partitionsRead++;
                DataFile data = openFiles.computeIfAbsent(
                        partition.file(),
                        file -> DataFile.open(tableDirectory(tableName).resolve(file)));

                rows.addAll(readMatchingRows(
                        data, columns, partition, columnIndex, comparison, constant));
            }
        } finally {
            for (DataFile data : openFiles.values()) {
                data.close();
            }
        }

        lastScanStats = new ScanStats(partitions.size(), partitionsRead, partitionsPruned);

        LOGGER.debug(
                "table={} column={} comparison={} const={} partitionsTotal={} partitionsRead={}"
                + " partitionsPruned={} rowsOut={} durationMs={}",
                field(tableName), field(columnName), comparison, field(constant),
                lastScanStats.partitionsTotal(), lastScanStats.partitionsRead(),
                lastScanStats.partitionsPruned(), rows.size(), millisSince(startedAt));

        return rows;
    }

    public ScanStats lastScanStats() {
        return lastScanStats;
    }

    private static List<Object[]> readMatchingRows(DataFile data, List<ColumnSpec> columns,
                                                   PartitionMeta partition, int columnIndex,
                                                   Comparison comparison, Object constant) {
        ColumnSpec column = columns.get(columnIndex);
        List<Object> predicateValues = data.readChunk(
                partition.chunks().get(column.name()), column.type(), partition.rowCount());

        List<Integer> matching = new ArrayList<>();
        for (int row = 0; row < predicateValues.size(); row++) {
            if (Pruner.matches(comparison, predicateValues.get(row), constant)) {
                matching.add(row);
            }
        }
        if (matching.isEmpty()) {
            return List.of();
        }

        List<List<Object>> chunks = new ArrayList<>(columns.size());
        for (int c = 0; c < columns.size(); c++) {
            ColumnSpec other = columns.get(c);
            chunks.add(c == columnIndex
                    ? predicateValues
                    : data.readChunk(partition.chunks().get(other.name()),
                            other.type(), partition.rowCount()));
        }

        List<Object[]> rows = new ArrayList<>(matching.size());
        for (int row : matching) {
            Object[] values = new Object[columns.size()];
            for (int c = 0; c < columns.size(); c++) {
                values[c] = chunks.get(c).get(row);
            }
            rows.add(values);
        }
        return rows;
    }

    private static int columnIndex(List<ColumnSpec> columns, String columnName) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equals(columnName)) {
                return i;
            }
        }
        throw new IllegalArgumentException("unknown column: " + columnName);
    }

    private static void requireMatchingConstant(ColumnSpec column, Object constant) {
        Class<?> expected = switch (column.type()) {
            case STRING -> String.class;
            case LONG -> Long.class;
            case DOUBLE -> Double.class;
        };

        if (constant == null || constant.getClass() != expected) {
            throw new IllegalArgumentException(
                    "column " + column.name() + " is " + column.type()
                    + " so the constant must be " + expected.getSimpleName()
                    + " but was " + (constant == null ? "null" : constant.getClass().getSimpleName()));
        }
    }

    private static long millisSince(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private static String field(Object value) {
        return String.valueOf(value).replace(',', '_');
    }
}
