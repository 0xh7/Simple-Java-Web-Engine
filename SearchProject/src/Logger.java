import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class Logger {
    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public enum Level { DEBUG, INFO, WARN, ERROR }

    private static Level current = Level.INFO;

    public static void setLevel(Level lvl) { current = (lvl != null ? lvl : Level.INFO); }

    public static void debug(String msg) { log(Level.DEBUG, msg, null); }
    public static void info(String msg)  { log(Level.INFO,  msg, null); }
    public static void warn(String msg)  { log(Level.WARN,  msg, null); }
    public static void error(String msg) { log(Level.ERROR, msg, null); }

    public static void error(String msg, Throwable t) { log(Level.ERROR, msg, t); }

    private static void log(Level lvl, String msg, Throwable t) {
        if (lvl.ordinal() < current.ordinal()) return;
        String prefix = "[" + F.format(LocalDateTime.now()) + "][" + lvl + "] ";
        if (lvl.ordinal() >= Level.WARN.ordinal()) {
            System.err.println(prefix + msg);
            if (t != null) t.printStackTrace(System.err);
        } else {
            System.out.println(prefix + msg);
            if (t != null) t.printStackTrace(System.out);
        }
    }
}
