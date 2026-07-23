package org.apache.logging.log4j;

public interface Logger {
    default void debug(String message, Object... arguments) {}
    default void info(String message, Object... arguments) {}
    default void warn(String message, Object... arguments) {}
    default void error(String message, Object... arguments) {}
}
