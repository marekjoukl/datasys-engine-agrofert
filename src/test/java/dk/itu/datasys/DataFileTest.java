package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataFileTest {

    @Test
    void readsALongChunk(@TempDir Path dir) throws Exception {
        ByteBuffer buffer = ValueCodec.allocate(DataFile.HEADER_SIZE + 16);
        DataFile.writeHeader(buffer);
        ValueCodec.write(buffer, ColumnType.LONG, 12L);
        ValueCodec.write(buffer, ColumnType.LONG, 31L);

        try (DataFile data = DataFile.open(write(dir, buffer))) {
            assertEquals(
                    List.of(12L, 31L),
                    data.readChunk(chunk(DataFile.HEADER_SIZE, 16), ColumnType.LONG, 2));
        }
    }

    @Test
    void readsADoubleChunk(@TempDir Path dir) throws Exception {
        ByteBuffer buffer = ValueCodec.allocate(DataFile.HEADER_SIZE + 16);
        DataFile.writeHeader(buffer);
        ValueCodec.write(buffer, ColumnType.DOUBLE, 23.5);
        ValueCodec.write(buffer, ColumnType.DOUBLE, 450.25);

        try (DataFile data = DataFile.open(write(dir, buffer))) {
            assertEquals(
                    List.of(23.5, 450.25),
                    data.readChunk(chunk(DataFile.HEADER_SIZE, 16), ColumnType.DOUBLE, 2));
        }
    }

    @Test
    void readsAStringChunkThatFollowsAnotherColumn(@TempDir Path dir) throws Exception {
        ByteBuffer buffer = ValueCodec.allocate(64);
        DataFile.writeHeader(buffer);
        ValueCodec.write(buffer, ColumnType.LONG, 210L);
        long cityOffset = buffer.position();
        ValueCodec.write(buffer, ColumnType.STRING, "Aalborg");
        ValueCodec.write(buffer, ColumnType.STRING, "Odense");
        int cityLength = (int) (buffer.position() - cityOffset);

        try (DataFile data = DataFile.open(write(dir, buffer))) {
            assertEquals(
                    List.of("Aalborg", "Odense"),
                    data.readChunk(chunk(cityOffset, cityLength), ColumnType.STRING, 2));
        }
    }

    @Test
    void readsAHeaderOnlyFile(@TempDir Path dir) throws Exception {
        ByteBuffer buffer = ValueCodec.allocate(DataFile.HEADER_SIZE);
        DataFile.writeHeader(buffer);

        try (DataFile data = DataFile.open(write(dir, buffer))) {
            assertEquals(List.of(), data.readChunk(chunk(DataFile.HEADER_SIZE, 0), ColumnType.LONG, 0));
        }
    }

    @Test
    void rejectsAFileWithoutTheMagicBytes(@TempDir Path dir) throws Exception {
        ByteBuffer buffer = ValueCodec.allocate(DataFile.HEADER_SIZE);
        buffer.put(new byte[] {'N', 'O', 'P', 'E'});
        buffer.putShort(DataFile.FORMAT_VERSION);
        Path file = write(dir, buffer);

        IllegalStateException e = assertThrows(
                IllegalStateException.class, () -> DataFile.open(file));

        assertTrue(e.getMessage().contains("not an engine data file"), e.getMessage());
    }

    @Test
    void rejectsAnUnknownFormatVersion(@TempDir Path dir) throws Exception {
        ByteBuffer buffer = ValueCodec.allocate(DataFile.HEADER_SIZE);
        buffer.put(DataFile.MAGIC);
        buffer.putShort((short) (DataFile.FORMAT_VERSION + 1));
        Path file = write(dir, buffer);

        IllegalStateException e = assertThrows(
                IllegalStateException.class, () -> DataFile.open(file));

        assertTrue(e.getMessage().contains("format version"), e.getMessage());
    }

    @Test
    void rejectsAFileTooShortForAHeader(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("trips.data");
        Files.write(file, new byte[] {'A', 'G', 'R'});

        assertThrows(IllegalStateException.class, () -> DataFile.open(file));
    }

    @Test
    void rejectsAChunkThatRunsPastTheEndOfTheFile(@TempDir Path dir) throws Exception {
        ByteBuffer buffer = ValueCodec.allocate(DataFile.HEADER_SIZE + 8);
        DataFile.writeHeader(buffer);
        ValueCodec.write(buffer, ColumnType.LONG, 12L);

        try (DataFile data = DataFile.open(write(dir, buffer))) {
            assertThrows(
                    IllegalStateException.class,
                    () -> data.readChunk(chunk(DataFile.HEADER_SIZE, 16), ColumnType.LONG, 2));
        }
    }

    private static ChunkMeta chunk(long offset, int length) {
        return new ChunkMeta(offset, length, "unused", "unused");
    }

    private static Path write(Path dir, ByteBuffer buffer) throws IOException {
        buffer.flip();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);

        Path file = dir.resolve("trips.data");
        Files.write(file, bytes);
        return file;
    }
}
