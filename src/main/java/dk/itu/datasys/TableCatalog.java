package dk.itu.datasys;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

record TableCatalog(String name, List<ColumnSpec> columns, boolean copied, List<PartitionMeta> partitions) {
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
        try {
            return MAPPER.readValue(file.toFile(), TableCatalog.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
 }