package org.apache.logging.log4j;

public final class LogManager {
    private static final Logger LOGGER = new Logger() {};

    private LogManager() {}

    public static Logger getLogger(Class<?> ignored) {
        return LOGGER;
    }
}
