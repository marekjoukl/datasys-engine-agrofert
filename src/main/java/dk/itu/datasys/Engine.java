package dk.itu.datasys;

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

    private static final String USAGE = """
            %s engine

            usage:
              engine                     print this message
              engine -c "<sql>"          run one statement
              engine "<sql>"             same as -c
              engine -f <script.sql>     run every statement in the file

            Rows are printed to stdout as headerless CSV. Logs and errors go to stderr.
            Tables live under %s/ in the working directory.""".formatted(TEAM,
            DEFAULT_DATA_DIRECTORY);

    public static void main(String[] args) {
        System.exit(run(args, System.out));
    }

    static int run(String[] args, PrintStream out) {
        return run(args, out, DEFAULT_DATA_DIRECTORY);
    }

    static int run(String[] args, PrintStream out, Path dataDirectory) {
        LoggingContext.initialise();
        LOGGER.debug("engine started args={}", args.length);

        int status = 0;
        try {
            String sql = sqlFrom(args);
            if (sql == null) {
                out.println(USAGE);
            } else {
                new Executor(new StorageEngine(dataDirectory), out).run(sql);
            }
        } catch (RuntimeException e) {
            System.err.println(message(e));
            status = 1;
        }

        LOGGER.debug("engine stopped status={}", status);
        return status;
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
