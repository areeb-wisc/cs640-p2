package edu.wisc.cs.sdn.vnet.logging;

public enum Level {

    ERROR(4),
    WARN(3),
    DEBUG(2),
    INFO(1);

    private final int level;
    Level(int level) {
        this.level = level;
    }

    boolean greater(Level other) {
        return this.level > other.level;
    }
}
