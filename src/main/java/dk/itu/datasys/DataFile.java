package dk.itu.datasys;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class DataFile implements Closeable {

    static final byte[] MAGIC = {'A', 'G', 'R', 'O'};
    static final short FORMAT_VERSION = 1;
    static final int HEADER_SIZE = MAGIC.length + Short.BYTES;

    private final FileChannel channel;
    private final String name;

    private DataFile(FileChannel channel, String name) {
        this.channel = channel;
        this.name = name;
    }

    static void writeHeader(ByteBuffer buffer) {
        buffer.put(MAGIC);
        buffer.putShort(FORMAT_VERSION);
    }

    static DataFile open(Path file) {
        FileChannel channel;
        try {
            channel = FileChannel.open(file, StandardOpenOption.READ);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        DataFile dataFile = new DataFile(channel, file.getFileName().toString());
        try {
            dataFile.verifyHeader();
        } catch (RuntimeException e) {
            dataFile.close();
            throw e;
        }
        return dataFile;
    }

    private void verifyHeader() {
        ByteBuffer header = readFully(0, HEADER_SIZE);

        byte[] magic = new byte[MAGIC.length];
        header.get(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new IllegalStateException(name + " is not an engine data file");
        }

        short version = header.getShort();
        if (version != FORMAT_VERSION) {
            throw new IllegalStateException(
                    name + " has format version " + version
                    + " but this engine reads version " + FORMAT_VERSION);
        }
    }

    List<Object> readChunk(ChunkMeta chunk, ColumnType type, int rowCount) {
        ByteBuffer buffer = readFully(chunk.offset(), chunk.length());

        List<Object> values = new ArrayList<>(rowCount);
        for (int i = 0; i < rowCount; i++) {
            values.add(ValueCodec.read(buffer, type));
        }
        return values;
    }

    private ByteBuffer readFully(long offset, int length) {
        ByteBuffer buffer = ValueCodec.allocate(length);
        try {
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer, offset + buffer.position());
                if (read < 0) {
                    throw new IllegalStateException(
                            name + " ended after " + buffer.position() + " of " + length
                            + " bytes at offset " + offset);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        buffer.flip();
        return buffer;
    }

    @Override
    public void close() {
        try {
            channel.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
