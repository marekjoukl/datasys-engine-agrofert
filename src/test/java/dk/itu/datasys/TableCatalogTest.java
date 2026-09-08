package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TableCatalogTest {

    @Test
    void savesAndLoadsSchema(@TempDir Path dir) {
        TableCatalog catalog = new TableCatalog(
                "trips",
                List.of(new ColumnSpec("city", ColumnType.STRING),
                        new ColumnSpec("distance", ColumnType.LONG)),
                false,
                List.of());

        Path file = dir.resolve("catalog.json");
        TableCatalog.save(catalog, file);

        assertEquals(catalog, TableCatalog.load(file));
    }

    @Test
    void savesAndLoadsPartitionSummaries(@TempDir Path dir) {
        TableCatalog catalog = new TableCatalog(
                "trips",
                List.of(new ColumnSpec("distance", ColumnType.LONG)),
                true,
                List.of(new PartitionMeta(
                        "trips.data",
                        2,
                        Map.of("distance", new ChunkMeta(0L, 16, "12", "31")))));

        Path file = dir.resolve("catalog.json");
        TableCatalog.save(catalog, file);

        assertEquals(catalog, TableCatalog.load(file));
    }
}
