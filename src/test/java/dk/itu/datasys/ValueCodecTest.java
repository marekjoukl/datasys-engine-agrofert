package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.jupiter.api.Test;
class ValueCodecTest {

    @Test
    void longRoundTrip() {
        ByteBuffer buffer = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        ValueCodec.write(buffer, ColumnType.LONG, 42L);
        buffer.flip();
        assertEquals(42L, ValueCodec.read(buffer, ColumnType.LONG));
    }
    @Test
    void doubleRoundTrip() {
        ByteBuffer buffer = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
        ValueCodec.write(buffer, ColumnType.DOUBLE, 42D);
        buffer.flip();
        assertEquals(42.0, ValueCodec.read(buffer, ColumnType.DOUBLE));
    }
    @Test
    void stringRoundTrip() {
        ByteBuffer buffer = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        ValueCodec.write(buffer, ColumnType.STRING, "Copenhagen");
        buffer.flip();
        assertEquals("Copenhagen", ValueCodec.read(buffer, ColumnType.STRING));
    }
    @Test
    void multipleValues() {
        ByteBuffer buffer = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        ValueCodec.write(buffer, ColumnType.STRING, "Aarhus");
        ValueCodec.write(buffer, ColumnType.LONG, 187L);
        buffer.flip();

        assertEquals("Aarhus", ValueCodec.read(buffer, ColumnType.STRING));
        assertEquals(187L, ValueCodec.read(buffer, ColumnType.LONG));
    }

    @Test
    void encodedSizeMatchesBytesWritten() {
        ByteBuffer buffer = ValueCodec.allocate(64);
        ValueCodec.write(buffer, ColumnType.STRING, "Copenhagen");

        assertEquals(buffer.position(), ValueCodec.encodedSize(ColumnType.STRING, "Copenhagen"));
    }
}