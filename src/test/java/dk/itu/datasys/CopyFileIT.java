package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
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

        // distance column, partition by partition
        assertMinMax(partitions.get(0), "distance", "12", "187");
        assertMinMax(partitions.get(1), "distance", "95", "140");
        assertMinMax(partitions.get(2), "distance", "31", "210");
        assertMinMax(partitions.get(3), "distance", "88", "299");

        // lexicographic ordering on a STRING column
        assertMinMax(partitions.get(0), "city", "Aarhus", "Copenhagen");
        assertMinMax(partitions.get(2), "city", "Aalborg", "Roskilde");

        assertMinMax(partitions.get(0), "price", "23.5", "301.0");
        assertMinMax(partitions.get(3), "price", "99.99", "450.25");
    }

    @Test
    void chunkOffsetsAreContiguousAfterTheHeader(@TempDir Path dir) throws Exception {
        List<PartitionMeta> partitions = copyGolden(dir, 2).partitions();

        long expectedOffset = 6; // 4 magic bytes + 2 version bytes
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
    void dataFileStartsWithMagicBytesAndVersion(@TempDir Path dir) throws Exception {
        copyGolden(dir, 2);

        byte[] bytes = Files.readAllBytes(dir.resolve("trips").resolve("trips.data"));

        assertEquals('A', bytes[0]);
        assertEquals('G', bytes[1]);
        assertEquals('R', bytes[2]);
        assertEquals('O', bytes[3]);
        assertEquals(1, bytes[4]); // version 1, little-endian short
        assertEquals(0, bytes[5]);
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
    void copyIntoUnknownTableIsRejected(@TempDir Path dir) {
        StorageEngine engine = new StorageEngine(dir);

        assertThrows(
                IllegalArgumentException.class,
                () -> engine.copyFile("nope", GOLDEN_CSV));
    }

    @Test
    void secondCopyIsRejected(@TempDir Path dir) {
        StorageEngine engine = new StorageEngine(dir, 2);
        engine.createTable("trips", TRIPS);
        engine.copyFile("trips", GOLDEN_CSV);

        assertThrows(
                UnsupportedOperationException.class,
                () -> engine.copyFile("trips", GOLDEN_CSV));
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
    void secondCopyIsRejectedEvenWhenTheFirstWasEmpty(@TempDir Path dir) throws Exception {
        Path emptyCsv = dir.resolve("empty.csv");
        Files.writeString(emptyCsv, "");

        StorageEngine engine = new StorageEngine(dir, 2);
        engine.createTable("trips", TRIPS);
        engine.copyFile("trips", emptyCsv.toString());

        TableCatalog catalog = TableCatalog.load(dir.resolve("trips").resolve("catalog.json"));
        assertTrue(catalog.partitions().isEmpty(), "an empty CSV produces no partitions");
        assertTrue(catalog.copied(), "the table has still been copied into");

        assertThrows(
                UnsupportedOperationException.class,
                () -> engine.copyFile("trips", GOLDEN_CSV));
    }

    private static void assertMinMax(PartitionMeta partition, String column, String min, String max) {
        ChunkMeta chunk = partition.chunks().get(column);
        assertEquals(min, chunk.min(), column + " min");
        assertEquals(max, chunk.max(), column + " max");
    }
}
