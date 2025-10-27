package util;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class Logger {

    public enum Level { DEBUG, INFO, WARN, ERROR }

    private static DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static Level current = Level.INFO;

    public static void setLevel(Level lvl) {
        if (lvl == null) {
            current = Level.INFO;
        } else {
            current = lvl;
        }
    }

    public static void setFormat(String pattern) {
        if (pattern != null && !pattern.isBlank()) {
            F = DateTimeFormatter.ofPattern(pattern);
        }
    }

    public static void debug(String msg) {
        log(Level.DEBUG, msg, null);
    }

    public static void info(String msg) {
        log(Level.INFO, msg, null);
    }

    public static void warn(String msg) {
        log(Level.WARN, msg, null);
    }

    public static void error(String msg) {
        log(Level.ERROR, msg, null);
    }

    public static void error(String msg, Throwable t) {
        log(Level.ERROR, msg, t);
    }

    private static void log(Level lvl, String msg, Throwable t) {
        if (lvl.ordinal() < current.ordinal()) {
            return;
        }
        String prefix = "[" + F.format(LocalDateTime.now()) + "][" + lvl + "] ";
        java.io.PrintStream out = streamFor(lvl);
        out.println(prefix + msg);
        if (t != null) {
            t.printStackTrace(out);
        }
    }

    private static java.io.PrintStream streamFor(Level lvl) {
        if (lvl.ordinal() >= Level.WARN.ordinal()) {
            return System.err;
        } else {
            return System.out;
        }
    }
}
