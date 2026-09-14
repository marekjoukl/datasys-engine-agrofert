package dk.itu.datasys;

import java.util.UUID;

import org.slf4j.MDC;
public final class LoggingContext {

    private LoggingContext() {
    }

    public static void initialise() {
        if (MDC.get("sessionId") == null) {
            MDC.put("sessionId", UUID.randomUUID().toString());
        }
        if (MDC.get("statementNumber") == null) {
            MDC.put("statementNumber", "0");
        }
    }
}
