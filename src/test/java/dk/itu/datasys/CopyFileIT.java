package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CopyFileIT {

    private static final String GOLDEN_CSV = "src/test/resources/trips.csv";

    private static final List<ColumnSpec> TRIPS = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    private static TableCatalog copyGolden(Path dir, int maxRowsPerPartition) {
        StorageEngine engine = new StorageEngine(dir, maxRowsPerPartition);
        engine.createTable("trips", TRIPS);
        engine.copyFile("trips", GOLDEN_CSV);
        return TableCatalog.load(dir.resolve("trips").resolve("catalog.json"));
    }

    @Test
    void partitioningProducesFourPartitions(@TempDir Path dir) {
        TableCatalog catalog = copyGolden(dir, 2);

        assertEquals(4, catalog.partitions().size());
        for (PartitionMeta partition : catalog.partitions()) {
            assertEquals(2, partition.rowCount());
            assertEquals(3, partition.chunks().size());
        }
    }

    @Test
    void partitionMinMaxMatchesTheGoldenData(@TempDir Path dir) {
        List<PartitionMeta> partitions = copyGolden(dir, 2).partitions();

        assertMinMax(partitions.get(0), "distance", "12", "187");
        assertMinMax(partitions.get(1), "distance", "95", "140");
        assertMinMax(partitions.get(2), "distance", "31", "210");
        assertMinMax(partitions.get(3), "distance", "88", "299");

        assertMinMax(partitions.get(0), "city", "Aarhus", "Copenhagen");
        assertMinMax(partitions.get(2), "city", "Aalborg", "Roskilde");

        assertMinMax(partitions.get(0), "price", "23.5", "301.0");
        assertMinMax(partitions.get(3), "price", "99.99", "450.25");
    }

    @Test
    void chunkOffsetsAreContiguousAfterTheHeader(@TempDir Path dir) throws Exception {
        List<PartitionMeta> partitions = copyGolden(dir, 2).partitions();

        byte[] header = Files.readAllBytes(dir.resolve("trips").resolve("trips.data"));
        assertArrayEquals(new byte[] {'A', 'G', 'R', 'O', 1, 0},
                java.util.Arrays.copyOf(header, 6), "magic bytes and little-endian version 1");

        long expectedOffset = 6;
        for (PartitionMeta partition : partitions) {
            for (ColumnSpec column : TRIPS) {
                ChunkMeta chunk = partition.chunks().get(column.name());
                assertEquals(expectedOffset, chunk.offset(),
                        "chunk for " + column.name() + " starts where the previous one ended");
                expectedOffset += chunk.length();
            }
        }

        assertEquals(expectedOffset, Files.size(dir.resolve("trips").resolve("trips.data")),
                "the chunks should account for the whole file");
    }


    @Test
    void defaultPartitionSizeKeepsTheGoldenFileInOnePartition(@TempDir Path dir) {
        StorageEngine engine = new StorageEngine(dir);
        engine.createTable("trips", TRIPS);
        engine.copyFile("trips", GOLDEN_CSV);

        TableCatalog catalog = TableCatalog.load(dir.resolve("trips").resolve("catalog.json"));

        assertEquals(1, catalog.partitions().size());
        assertEquals(8, catalog.partitions().get(0).rowCount());
    }

    @Test
    void copyCommitsVersionOneAndReportsWhatItWrote(@TempDir Path dir) {
        StorageEngine engine = new StorageEngine(dir, 2);
        engine.createTable("trips", TRIPS);

        CopyResult result = engine.copyFile("trips", GOLDEN_CSV);

        assertEquals(new CopyResult(8, 4, 1), result);
        assertEquals(1, TableCatalog.load(dir.resolve("trips").resolve("catalog.json")).version());
    }

    @Test
    void copyIntoUnknownTableIsRejected(@TempDir Path dir) {
        StorageEngine engine = new StorageEngine(dir);

        assertThrows(
                IllegalArgumentException.class,
                () -> engine.copyFile("nope", GOLDEN_CSV));
    }

    @Test
    void aSecondCopyAppendsItsPartitionsAndCommitsTheNextVersion(@TempDir Path dir) {
        StorageEngine engine = new StorageEngine(dir, 2);
        engine.createTable("trips", TRIPS);
        engine.copyFile("trips", GOLDEN_CSV);
        List<PartitionMeta> first = engine.snapshot("trips").partitions();

        CopyResult second = engine.copyFile("trips", GOLDEN_CSV);

        assertEquals(new CopyResult(8, 4, 2), second);
        Snapshot snapshot = engine.snapshot("trips");
        assertEquals(8, snapshot.partitions().size());
        assertEquals(first, snapshot.partitions().subList(0, 4),
                "the first copy's partitions keep their offsets");
        assertEquals(2, TableCatalog.load(dir.resolve("trips").resolve("catalog.json")).version());
    }

    @Test
    void aSecondCopyIsReadBackAfterTheFirst(@TempDir Path dir) {
        StorageEngine engine = new StorageEngine(dir, 2);
        engine.createTable("trips", TRIPS);
        engine.copyFile("trips", GOLDEN_CSV);
        List<Object[]> once = allRows(engine);

        engine.copyFile("trips", GOLDEN_CSV);
        List<Object[]> twice = allRows(engine);

        assertEquals(16, twice.size());
        for (int i = 0; i < once.size(); i++) {
            assertArrayEquals(once.get(i), twice.get(i));
            assertArrayEquals(once.get(i), twice.get(i + once.size()));
        }
    }

    @Test
    void chunkOffsetsStayContiguousAcrossTwoCopies(@TempDir Path dir) throws Exception {
        StorageEngine engine = new StorageEngine(dir, 2);
        engine.createTable("trips", TRIPS);
        engine.copyFile("trips", GOLDEN_CSV);
        engine.copyFile("trips", GOLDEN_CSV);

        long expectedOffset = 6;
        for (PartitionMeta partition : engine.snapshot("trips").partitions()) {
            for (ColumnSpec column : TRIPS) {
                ChunkMeta chunk = partition.chunks().get(column.name());
                assertEquals(expectedOffset, chunk.offset(),
                        "the second copy starts where the first one ended, with no second header");
                expectedOffset += chunk.length();
            }
        }
        assertEquals(expectedOffset, Files.size(dir.resolve("trips").resolve("trips.data")));
    }

    @Test
    void aFailedSecondCopyLeavesTheFirstVersion(@TempDir Path dir) throws Exception {
        Path badCsv = dir.resolve("bad.csv");
        Files.writeString(badCsv, "Copenhagen,12,23.5\nAarhus,not-a-number,301.0\n");

        StorageEngine engine = new StorageEngine(dir, 2);
        engine.createTable("trips", TRIPS);
        engine.copyFile("trips", GOLDEN_CSV);
        Snapshot before = engine.snapshot("trips");
        long sizeBefore = Files.size(dir.resolve("trips").resolve("trips.data"));

        assertThrows(IllegalArgumentException.class,
                () -> engine.copyFile("trips", badCsv.toString()));

        assertEquals(before, engine.snapshot("trips"));
        assertEquals(1, TableCatalog.load(dir.resolve("trips").resolve("catalog.json")).version());
        assertEquals(sizeBefore, Files.size(dir.resolve("trips").resolve("trips.data")));
    }

    @Test
    void malformedLineAbortsTheWholeCopy(@TempDir Path dir) throws Exception {
        Path badCsv = dir.resolve("bad.csv");
        Files.writeString(badCsv, """
                Copenhagen,12,23.5
                Aarhus,not-a-number,301.0
                Odense,95,120.75
                """);

        StorageEngine engine = new StorageEngine(dir, 2);
        engine.createTable("trips", TRIPS);

        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> engine.copyFile("trips", badCsv.toString()));

        assertTrue(e.getMessage().contains("bad.csv"), e.getMessage());
        assertTrue(e.getMessage().contains("2"), e.getMessage());

        TableCatalog catalog = TableCatalog.load(dir.resolve("trips").resolve("catalog.json"));
        assertTrue(catalog.partitions().isEmpty(), "a failed copy must not register partitions");
        assertTrue(Files.notExists(dir.resolve("trips").resolve("trips.data")),
                "a failed copy must not leave a data file");
    }

    @Test
    void anEmptyCopyCommitsNoVersionAndWritesNothing(@TempDir Path dir) throws Exception {
        Path emptyCsv = dir.resolve("empty.csv");
        Files.writeString(emptyCsv, "");

        StorageEngine engine = new StorageEngine(dir, 2);
        engine.createTable("trips", TRIPS);

        assertEquals(new CopyResult(0, 0, 0), engine.copyFile("trips", emptyCsv.toString()),
                "the result reports the version the copy read");

        TableCatalog catalog = TableCatalog.load(dir.resolve("trips").resolve("catalog.json"));
        assertEquals(0, catalog.version());
        assertTrue(catalog.partitions().isEmpty());
        assertTrue(Files.notExists(dir.resolve("trips").resolve("trips.data")),
                "an empty copy must not leave a data file");

        assertEquals(1, engine.copyFile("trips", GOLDEN_CSV).version(),
                "a later copy still commits the next version");
    }

    private static List<Object[]> allRows(StorageEngine engine) {
        List<Object[]> rows = new ArrayList<>();
        for (PartitionMeta partition : engine.snapshot("trips").partitions()) {
            rows.addAll(engine.readPartitionRows("trips", partition));
        }
        return rows;
    }

    private static void assertMinMax(PartitionMeta partition, String column, String min, String max) {
        ChunkMeta chunk = partition.chunks().get(column);
        assertEquals(min, chunk.min(), column + " min");
        assertEquals(max, chunk.max(), column + " max");
    }
}
