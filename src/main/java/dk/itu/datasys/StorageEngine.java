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

public final class StorageEngine {

    private static final int DEFAULT_MAX_ROWS_PER_PARTITION = 1024;

    private final Path dataDirectory;
    private final int maxRowsPerPartition;

    public StorageEngine(Path dataDirectory) {
        this(dataDirectory, DEFAULT_MAX_ROWS_PER_PARTITION);
    }

    public StorageEngine(Path dataDirectory, int maxRowsPerPartition) {
        this.dataDirectory = dataDirectory;
        this.maxRowsPerPartition = maxRowsPerPartition;
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
    }

    public void copyFile(String tableName, String csvFilePath) {
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
            }

            partitions.add(new PartitionMeta(dataFileName, partitionRows.size(), chunks));
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
}