package net.floodlightcontroller.packet;

import edu.wisc.cs.sdn.vnet.Iface;
import edu.wisc.cs.sdn.vnet.logging.Level;
import edu.wisc.cs.sdn.vnet.logging.Logger;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.LinkedList;

/**
 * @author Aaron Gember-Jacobson and Anubhavnidhi Abhashkumar
 */
public class RIPv2 extends BasePacket 
{
    public static final byte VERSION = 2;
    public static final byte COMMAND_REQUEST = 1;
    public static final byte COMMAND_RESPONSE = 2;
    public static final int MULTICAST_ADDRESS = IPv4.toIPv4Address("224.0.0.9");
    public static final MACAddress BROADCAST_MAC = MACAddress.valueOf("FF:FF:FF:FF:FF:FF");
    private static final int timeToLive = 30000;
    private static final Logger logger = new Logger();

	protected byte command;
	protected byte version;
	protected List<RIPv2Entry> entries;

	public RIPv2()
	{ 
        super(); 
        this.version = VERSION;
        this.entries = new LinkedList<RIPv2Entry>();
    }

	public void setEntries(List<RIPv2Entry> entries)
	{ this.entries = entries; }

	public List<RIPv2Entry> getEntries()
	{ return this.entries; }

    public void addEntry(RIPv2Entry entry)
    { this.entries.add(entry); }
	
	public void setCommand(byte command)
	{ this.command = command; }

	public byte getCommand()
	{ return this.command; }

    private void cleanup() {
        logger.log(Level.DEBUG, "Cleaning up");
        long currentTime = System.currentTimeMillis();
        this.entries.forEach(entry -> {
            if (entry.getMetric() == 0) {
                logger.log(Level.DEBUG, "ignoring own interface");
                return;
            }
            if (currentTime - entry.getTimestamp() > timeToLive) {
                logger.log(Level.DEBUG, "marking stale entry unreachable");
                entry.setMetric(RIPv2Entry.INFINITY);
            }
        });
    }

    public Ethernet handleRIPv2(byte command, MACAddress sourceMAC, MACAddress destMAC,
                                       int sourceIP, int destIP) {
        // make payload
        RIPv2 ripPayload = new RIPv2();
        ripPayload.setCommand(command);
        if (command == RIPv2.COMMAND_RESPONSE) {
            for (RIPv2Entry entry : this.entries) {
                ripPayload.addEntry(entry);
            }
        }

        // wrap in UDP segment
        UDP udpResponse = new UDP();
        udpResponse.setSourcePort(UDP.RIP_PORT);
        udpResponse.setDestinationPort(UDP.RIP_PORT);
        udpResponse.setPayload(ripPayload);

        // wrap in IPv4 packet
        IPv4 ipv4Response = new IPv4();
        ipv4Response.setProtocol(IPv4.PROTOCOL_UDP);
        ipv4Response.setSourceAddress(sourceIP);
        ipv4Response.setDestinationAddress(destIP);
        ipv4Response.setPayload(udpResponse);

        // wrap in Ethernet frame
        Ethernet ethResponse = new Ethernet();
        ethResponse.setEtherType(Ethernet.TYPE_IPv4);
        ethResponse.setSourceMACAddress(sourceMAC.toBytes());
        ethResponse.setDestinationMACAddress(destMAC.toBytes());
        ethResponse.setPayload(ipv4Response);

        return ethResponse;
    }

    public boolean mergeRIPv2Entries(List<RIPv2Entry> otherEntries, int nextHop) {
        logger.log(Level.DEBUG, "mergeRIPv2Entries()");
        cleanup();
        boolean changed = false;
        synchronized (this.entries) {
            for (RIPv2Entry otherEntry : otherEntries) {
                logger.log(Level.DEBUG, "\tlooking for: " + otherEntry);
                boolean found = false;
                for (RIPv2Entry entry : this.entries) {
                    if (Integer.compareUnsigned(
                        entry.getAddress(), otherEntry.getAddress()) == 0) {
                        logger.log(Level.DEBUG, "\tfound: " + entry);
                        found = true;
                        int minMetric = Math.min(entry.getMetric(),
                            Math.min(RIPv2Entry.INFINITY, 1 + otherEntry.getMetric()));
                        if (minMetric < entry.getMetric()) {
                            entry.setMetric(minMetric);
                            entry.setNextHopAddress(nextHop);
                            entry.updateTimeStamp();
                            changed = true;
                            logger.log(Level.DEBUG, "\tupdated to: " + entry);
                        }
                        break;
                    }
                }
                if (!found) {
                    logger.log(Level.DEBUG, "not found, adding entry");
                    changed = true;
                    RIPv2Entry newEntry =
                        new RIPv2Entry(otherEntry.getAddress(), otherEntry.getSubnetMask(),
                        Math.min(RIPv2Entry.INFINITY, 1 + otherEntry.getMetric()));
                    newEntry.setNextHopAddress(nextHop);
                    this.entries.add(newEntry);
                }
            }
        }
        return changed;
    }

	@Override
	public byte[] serialize() 
    {
		int length = 1 + 1 + 2 + this.entries.size() * (5*4);
		byte[] data = new byte[length];
		ByteBuffer bb = ByteBuffer.wrap(data);

		bb.put(this.command);
		bb.put(this.version);
		bb.putShort((short)0); // Put padding
		for (RIPv2Entry entry : this.entries)
		{ bb.put(entry.serialize()); }

		return data;
	}

	@Override
	public IPacket deserialize(byte[] data, int offset, int length) 
	{
		ByteBuffer bb = ByteBuffer.wrap(data, offset, length);

		this.command = bb.get();
		this.version = bb.get();
        bb.getShort(); // Consume padding
		this.entries = new LinkedList<RIPv2Entry>();
        while (bb.position() < bb.limit())
        {
            RIPv2Entry entry = new RIPv2Entry();
            entry.deserialize(data, bb.position(), bb.limit()-bb.position());
            bb.position(bb.position() +  5*4);
            this.entries.add(entry);
        }
		return this;
	}

    public boolean equals(Object obj)
    {
        if (this == obj)
        { return true; }
        if (null == obj)
        { return false; }
        if (!(obj instanceof RIPv2))
        { return false; }
        RIPv2 other = (RIPv2)obj;
        if (this.command != other.command)
        { return false; }
        if (this.version != other.version)
        { return false; }
        if (this.entries.size() != other.entries.size())
        { return false; }
        for (int i = 0; i < this.entries.size(); i++)
        {
            if (!this.entries.get(i).equals(other.entries.get(i)))
            { return false; }
        }
        return true; 
    }

	public String toString()
	{
		String x = String.format("RIP : {command=%d, version=%d, entries={",
                this.command, this.version);
		for (RIPv2Entry entry : this.entries)
		{ x = x + entry.toString() + ","; }
        x = x + "}}";
		return x;
	}
}
