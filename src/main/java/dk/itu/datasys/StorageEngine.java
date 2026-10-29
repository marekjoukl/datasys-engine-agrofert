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
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class StorageEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(StorageEngine.class);

    private static final int DEFAULT_MAX_ROWS_PER_PARTITION = 1024;

    private final Path dataDirectory;
    private final int maxRowsPerPartition;
    private final Map<String, TableVersions> tables = new ConcurrentHashMap<>();

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
        LoggingContext.initialise();
    }

    private Path tableDirectory(String tableName) {
        return dataDirectory.resolve(tableName);
    }

    private Path catalogFile(String tableName) {
        return tableDirectory(tableName).resolve("catalog.json");
    }

    private TableVersions requireTable(String tableName) {
        TableVersions cached = tables.get(tableName);
        if (cached != null) {
            return cached;
        }
        if (!Files.exists(catalogFile(tableName))) {
            throw new IllegalArgumentException("unknown table: " + tableName);
        }
        return tables.computeIfAbsent(tableName,
                name -> TableVersions.from(TableCatalog.load(catalogFile(name))));
    }

    private int commit(TableVersions table, int base, List<PartitionMeta> partitions) {
        int version = table.commit(base, partitions);
        saveNewest(table, version, partitions);
        return version;
    }

    private void saveNewest(TableVersions table, int version, List<PartitionMeta> partitions) {
        TableCatalog.save(new TableCatalog(table.name(), table.columns(), version, partitions),
                catalogFile(table.name()));
    }

    Snapshot snapshot(String tableName) {
        return requireTable(tableName).snapshot();
    }

    public List<ColumnSpec> schema(String tableName) {
        return requireTable(tableName).columns();
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

        TableVersions table = new TableVersions(tableName, columns, 0, List.of());
        saveNewest(table, 0, List.of());
        tables.put(tableName, table);

        LOGGER.debug("table={} columns={} durationMs={}",
                field(tableName), columns.size(), millisSince(startedAt));
    }

    public CopyResult copyFile(String tableName, String csvFilePath) {
        long startedAt = System.nanoTime();
        TableVersions table = requireTable(tableName);
        Snapshot base = table.snapshot();

        List<Object[]> rows = readCsv(csvFilePath, table.columns());
        if (rows.isEmpty()) {
            return new CopyResult(0, 0, base.version());
        }
        List<PartitionMeta> partitions = appendPartitions(tableName, table.columns(), rows);

        List<PartitionMeta> next = new ArrayList<>(base.partitions());
        next.addAll(partitions);
        int version = commit(table, base.version(), next);

        LOGGER.debug("table={} file={} rows={} maxRowsPerPartition={} partitions={} durationMs={}",
                field(tableName), field(Path.of(csvFilePath).getFileName()),
                rows.size(), maxRowsPerPartition, partitions.size(), millisSince(startedAt));
        return new CopyResult(rows.size(), partitions.size(), version);
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

    private List<PartitionMeta> appendPartitions(String tableName, List<ColumnSpec> columns, List<Object[]> rows) {
        String dataFileName = tableName + ".data";
        try (FileChannel channel = FileChannel.open(
                tableDirectory(tableName).resolve(dataFileName),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE)) {
            long fileEnd = channel.size();
            boolean newFile = fileEnd == 0;

            int totalSize = newFile ? DataFile.HEADER_SIZE : 0;
            for (Object[] row : rows) {
                for (int c = 0; c < columns.size(); c++) {
                    totalSize += ValueCodec.encodedSize(columns.get(c).type(), row[c]);
                }
            }

            ByteBuffer buffer = ValueCodec.allocate(totalSize);
            if (newFile) {
                DataFile.writeHeader(buffer);
            }
            List<PartitionMeta> partitions = encodePartitions(
                    tableName, columns, rows, buffer, fileEnd, dataFileName);

            buffer.flip();
            while (buffer.hasRemaining()) {
                channel.write(buffer, fileEnd + buffer.position());
            }
            return partitions;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<PartitionMeta> encodePartitions(String tableName, List<ColumnSpec> columns,
            List<Object[]> rows, ByteBuffer buffer, long fileEnd, String dataFileName) {
        List<PartitionMeta> partitions = new ArrayList<>();
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

                int position = buffer.position();
                for (Object value : values) {
                    ValueCodec.write(buffer, column.type(), value);
                }
                int length = buffer.position() - position;

                MinMax minMax = Summaries.compute(column.type(), values);
                chunks.put(column.name(), new ChunkMeta(
                        fileEnd + position, length,
                        String.valueOf(minMax.min()),
                        String.valueOf(minMax.max())));

                LOGGER.debug("table={} partition={} column={} min={} max={}",
                        field(tableName), partitionIndex, field(column.name()),
                        field(minMax.min()), field(minMax.max()));
            }

            partitions.add(new PartitionMeta(dataFileName, partitionRows.size(), chunks));
            partitionIndex++;
        }
        return partitions;
    }

    public List<Object[]> readPartitionRows(String tableName, int partitionNumber) {
        TableVersions table = requireTable(tableName);
        List<ColumnSpec> columns = table.columns();
        PartitionMeta partition = table.snapshot().partitions().get(partitionNumber);

        int rowCount = partition.rowCount();

        DataFile data = DataFile.open(tableDirectory(tableName).resolve(partition.file()));

        try {
            List<List<Object>> chunks = new ArrayList<>(columns.size());

            for(ColumnSpec column : columns) {
                chunks.add(data.readChunk(partition.chunks().get(column.name()), column.type(), rowCount));
            }
            List<Object[]> rows = new ArrayList<>(rowCount);
            for (int row = 0; row < rowCount; row++) {
                Object[] values = new Object[columns.size()];
                for (int c = 0; c < columns.size(); c++) {
                    values[c] = chunks.get(c).get(row);
                }
                rows.add(values);
            }
            return rows;
        } finally {
            data.close();
        }
    }

    public int partitionCount(String tableName) {
        return snapshot(tableName).partitions().size();
    }

    static int columnIndex(List<ColumnSpec> columns, String columnName) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equals(columnName)) {
                return i;
            }
        }
        throw new IllegalArgumentException("unknown column: " + columnName);
    }

    static long millisSince(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    static String field(Object value) {
        return String.valueOf(value).replace(',', '_');
    }

    List<MinMax> columnRanges(String tableName, int columnIndex) {
        TableVersions table = requireTable(tableName);
        ColumnSpec column = table.columns().get(columnIndex);
        List<PartitionMeta> partitions = table.snapshot().partitions();

        List<MinMax> ranges = new ArrayList<>(partitions.size());
        for (PartitionMeta partition : partitions) {
            ChunkMeta chunk = partition.chunks().get(column.name());
            ranges.add(Summaries.parse(column.type(), chunk.min(), chunk.max()));
        }
        return ranges;
    }
}
