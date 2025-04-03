package edu.wisc.cs.sdn.vnet.logging;

public class Logger {

    private static Level logLevel = Level.INFO;

    public void setLevel(Level level) {
        Logger.logLevel = level;
    }

    public void log(Level level, String message) {
        if (level.greater(logLevel)) {
            return;
        }
        switch (level) {
            case INFO:
                System.out.println(message);
                break;
            case DEBUG:
                System.out.println("DEBUG: " + message);
                break;
            case WARN:
                System.out.println("WARN: " + message);
                break;
            case ERROR:
                System.out.println("ERROR: " + message);
                break;
            default:
                System.out.println("ERROR: undefined level: " + level);
                break;
        }
    }
}