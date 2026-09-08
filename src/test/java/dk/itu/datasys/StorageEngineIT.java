package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageEngineIT {

    private static final List<ColumnSpec> TRIPS = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    @Test
    void schemaSurvivesRestart(@TempDir Path dir) {
        new StorageEngine(dir).createTable("trips", TRIPS);

        // A second engine on the same directory simulates a restart.
        StorageEngine restarted = new StorageEngine(dir);

        assertThrows(
                IllegalArgumentException.class,
                () -> restarted.createTable("trips", TRIPS),
                "a restarted engine should already know the table");

        TableCatalog onDisk = TableCatalog.load(dir.resolve("trips").resolve("catalog.json"));
        assertEquals("trips", onDisk.name());
        assertEquals(TRIPS, onDisk.columns());
        assertTrue(onDisk.partitions().isEmpty());
    }

    @Test
    void duplicateTableIsRejected(@TempDir Path dir) {
        StorageEngine engine = new StorageEngine(dir);
        engine.createTable("trips", TRIPS);

        assertThrows(
                IllegalArgumentException.class,
                () -> engine.createTable("trips", TRIPS));
    }

    @Test
    void emptyColumnListIsRejected(@TempDir Path dir) {
        StorageEngine engine = new StorageEngine(dir);

        assertThrows(
                IllegalArgumentException.class,
                () -> engine.createTable("trips", List.of()));
    }

    @Test
    void repeatedColumnNamesAreRejected(@TempDir Path dir) {
        StorageEngine engine = new StorageEngine(dir);

        assertThrows(
                IllegalArgumentException.class,
                () -> engine.createTable("trips", List.of(
                        new ColumnSpec("city", ColumnType.STRING),
                        new ColumnSpec("city", ColumnType.LONG))));
    }

    @Test
    void createTableWritesNoDataFiles(@TempDir Path dir) throws Exception {
        new StorageEngine(dir).createTable("trips", TRIPS);

        try (var entries = Files.list(dir.resolve("trips"))) {
            assertEquals(
                    List.of("catalog.json"),
                    entries.map(p -> p.getFileName().toString()).toList());
        }
    }
}
