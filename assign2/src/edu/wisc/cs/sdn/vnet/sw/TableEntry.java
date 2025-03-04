package edu.wisc.cs.sdn.vnet.sw;

import edu.wisc.cs.sdn.vnet.Iface;

public class TableEntry {
    private final Iface iface;
    private long timeAdded;

    public TableEntry(Iface iface) {
        this.iface = iface;
        this.timeAdded = System.currentTimeMillis();
    }

    public Iface getIface() {
        return iface;
    }

    public long getTimeAdded() {
        return this.timeAdded;
    }

    public void update() {
        this.timeAdded = System.currentTimeMillis();
    }
}
