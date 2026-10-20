package dk.itu.datasys;

import static dk.itu.datasys.StorageEngine.field;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Engine {

    private static final Logger LOGGER = LoggerFactory.getLogger(Engine.class);

    private static final String TEAM = "agrofert";
    private static final Path DEFAULT_DATA_DIRECTORY = Path.of("data");
    private static final String MAX_ROWS_PER_PARTITION = "engine.maxRowsPerPartition";

    private static final String USAGE = """
            %s engine

            usage:
              ./engine                     print this message
              ./engine -c "<sql>"          run one statement
              ./engine "<sql>"             same as -c
              ./engine -f <script.sql>     run every statement in the file

            Rows are printed to stdout as headerless CSV. Logs and errors go to stderr.
            Tables live under %s/ in the working directory.
            COPY splits rows into partitions of at most -D%s=<n> rows,
            e.g. ENGINE_JAVA_OPTS=-D%s=128 ./engine -f load.sql""".formatted(TEAM,
            DEFAULT_DATA_DIRECTORY, MAX_ROWS_PER_PARTITION, MAX_ROWS_PER_PARTITION);

    public static void main(String[] args) {
        System.exit(run(args, System.out));
    }

    static int run(String[] args, PrintStream out) {
        return run(args, out, DEFAULT_DATA_DIRECTORY);
    }

    static int run(String[] args, PrintStream out, Path dataDirectory) {
        LoggingContext.initialise();
        LOGGER.debug("engine started args={}", args.length);
        int status = execute(args, out, dataDirectory);
        LOGGER.debug("engine stopped status={}", status);
        return status;
    }

    private static int execute(String[] args, PrintStream out, Path dataDirectory) {
        String sql;
        Executor executor;
        try {
            sql = sqlFrom(args);
            if (sql == null) {
                out.println(USAGE);
                return 0;
            }
            executor = new Executor(storageEngine(dataDirectory), out);
        } catch (RuntimeException e) {
            LOGGER.error("front door failed error={} message={}",
                    e.getClass().getSimpleName(), field(message(e)));
            return failed(e);
        }

        try {
            executor.run(sql);
            return 0;
        } catch (RuntimeException e) {
            return failed(e);
        }
    }

    private static int failed(RuntimeException e) {
        System.err.println(message(e));
        return 1;
    }

    private static StorageEngine storageEngine(Path dataDirectory) {
        String maxRowsPerPartition = System.getProperty(MAX_ROWS_PER_PARTITION);
        if (maxRowsPerPartition == null) {
            return new StorageEngine(dataDirectory);
        }
        try {
            return new StorageEngine(dataDirectory, Integer.parseInt(maxRowsPerPartition));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(MAX_ROWS_PER_PARTITION
                    + " must be a whole number but was " + maxRowsPerPartition, e);
        }
    }

    private static String sqlFrom(String[] args) {
        return switch (args.length) {
            case 0 -> null;
            case 1 -> isFlag(args[0]) ? null : terminated(args[0]);
            case 2 -> switch (args[0]) {
                case "-c" -> terminated(args[1]);
                case "-f" -> readScript(args[1]);
                default -> throw new IllegalArgumentException("unknown option: " + args[0]);
            };
            default -> throw new IllegalArgumentException(
                    "expected at most 2 arguments but got " + args.length);
        };
    }

    private static boolean isFlag(String argument) {
        return argument.equals("-h") || argument.equals("--help");
    }

    private static String terminated(String sql) {
        String trimmed = sql.strip();
        return trimmed.endsWith(";") ? trimmed : trimmed + ";";
    }

    private static String readScript(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read script " + path, e);
        }
    }

    private static String message(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private Engine() {
    }
}
