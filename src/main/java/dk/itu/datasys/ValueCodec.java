package dk.itu.datasys;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

final class ValueCodec {
    static void write(ByteBuffer buffer, ColumnType type, Object value) {
        if (type == ColumnType.LONG) {
            buffer.putLong((Long) value);
        } else if (type == ColumnType.DOUBLE) {
            buffer.putDouble((Double) value);
        }  else if (type == ColumnType.STRING) {
            byte[] bytes = ((String) value).getBytes(StandardCharsets.US_ASCII);
            buffer.putInt(bytes.length);
            buffer.put(bytes);
        } else {
            throw new UnsupportedOperationException();
        }
    }

    static Object read (ByteBuffer buffer, ColumnType type){
        if (type == ColumnType.LONG) {
            return buffer.getLong();
        } else if (type == ColumnType.DOUBLE) {
            return buffer.getDouble();
        } else if (type == ColumnType.STRING) {
            int len = buffer.getInt();
            byte[] bytes = new byte[len];
            buffer.get(bytes); 
            return new String(bytes, StandardCharsets.US_ASCII);
        } else {
            throw new UnsupportedOperationException();
        }
    }
}