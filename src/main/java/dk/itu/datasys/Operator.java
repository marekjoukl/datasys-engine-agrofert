package dk.itu.datasys;

public interface Operator {
    void open();

    Object[] next();

    void close();
}
