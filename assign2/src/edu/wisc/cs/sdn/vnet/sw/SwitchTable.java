package edu.wisc.cs.sdn.vnet.sw;

import edu.wisc.cs.sdn.vnet.Iface;
import net.floodlightcontroller.packet.MACAddress;

import java.util.HashMap;
import java.util.Map;

public class SwitchTable {

    private static final int timeToLive = 15000;
    private final Map<MACAddress, TableEntry> table;

    public SwitchTable() {
        this.table = new HashMap<>();
    }

    private void cleanup() {
        long currentTime = System.currentTimeMillis();
        table.entrySet().removeIf(entry ->
                currentTime - entry.getValue().getTimeAdded() > timeToLive);
    }

    public void addEntry(MACAddress macAddress, Iface iface) {
        cleanup();
        table.put(macAddress, new TableEntry(iface));
    }

    public void updateEntry(MACAddress macAddress) {
        if (table.containsKey(macAddress)) {
            table.get(macAddress).update();
        }
    }

    public Iface getIface(MACAddress macAddress) {
        cleanup();
        if (table.containsKey(macAddress)) {
            table.get(macAddress).update();
            return table.get(macAddress).getIface();
        }
        return null;
    }

    public boolean hasEntry(MACAddress macAddress) {
    	return table.containsKey(macAddress);
    }
}
