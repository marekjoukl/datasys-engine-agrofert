package dk.itu.datasys;

import static dk.itu.datasys.StorageEngine.field;
import static dk.itu.datasys.StorageEngine.millisSince;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

record TableCatalog(String name, List<ColumnSpec> columns, boolean copied, List<PartitionMeta> partitions) {
    private static final Logger LOGGER = LoggerFactory.getLogger(TableCatalog.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static void save(TableCatalog catalog, Path file) {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), catalog);
            try {
                Files.move(tmp, file,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static TableCatalog load (Path file) {
        long startedAt = System.nanoTime();
        try {
            TableCatalog catalog = MAPPER.readValue(file.toFile(), TableCatalog.class);
            LOGGER.debug("loaded table={} partitions={} durationMs={}",
                    field(catalog.name()), catalog.partitions().size(), millisSince(startedAt));
            return catalog;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
 }