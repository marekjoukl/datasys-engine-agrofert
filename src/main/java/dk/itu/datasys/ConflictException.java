package dk.itu.datasys;

public final class ConflictException extends RuntimeException {

    private final String table;
    private final int base;
    private final int current;

    ConflictException(String table, int base, int current) {
        super("conflict on table " + table + ": started from version " + base
                + " but version " + current + " is already committed");
        this.table = table;
        this.base = base;
        this.current = current;
    }

    public String table() {
        return table;
    }

    public int base() {
        return base;
    }

    public int current() {
        return current;
    }
}
