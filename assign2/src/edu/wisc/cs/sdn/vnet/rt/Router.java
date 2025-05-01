package edu.wisc.cs.sdn.vnet.rt;

import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import edu.wisc.cs.sdn.vnet.logging.Level;
import edu.wisc.cs.sdn.vnet.logging.Logger;
import net.floodlightcontroller.packet.*;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * @author Aaron Gember-Jacobson and Anubhavnidhi Abhashkumar
 */
public class Router extends Device
{	
	/** Routing table for the router */
	private RouteTable routeTable;
	
	/** ARP cache for the router */
	private ArpCache arpCache;
	private boolean usingRIPv2 = false;
	private final RIPv2 ripHandler = new RIPv2();
	private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
	private static final Logger logger = new Logger();
	
	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Router(String host, DumpFile logfile)
	{
		super(host,logfile);
		this.routeTable = new RouteTable();
		this.arpCache = new ArpCache();
	}
	
	/**
	 * @return routing table for the router
	 */
	public RouteTable getRouteTable()
	{ return this.routeTable; }

	private void splitHorizon(byte command, List<Iface> ignore) {
		for (Map.Entry<String, Iface> entry: this.getInterfaces().entrySet()) {
			Iface iface = entry.getValue();
			if (ignore.contains(iface)) {
				continue;
			}
			logger.log(Level.DEBUG, "Sending RIPv2 update to "
				+ IPv4.fromIPv4Address(iface.getIpAddress()));
			this.sendPacket(ripHandler.getRIPv2Payload(command,
				iface.getMacAddress(), RIPv2.BROADCAST_MAC,
				iface.getIpAddress(), RIPv2.MULTICAST_ADDRESS), iface);
		}
	}

	private void sendRIPv2Update() {
		splitHorizon(RIPv2.COMMAND_RESPONSE, new ArrayList<>());
	}

	public void initRIPv2() {

		logger.log(Level.DEBUG, "Initializing RIPv2");
		usingRIPv2 = true;

		// add router's own subnets
		for (Map.Entry<String, Iface> entry: this.getInterfaces().entrySet()) {
			Iface iface = entry.getValue();
			RIPv2Entry riPv2Entry =
				new RIPv2Entry(iface.getIpAddress(), iface.getSubnetMask(), 0);
			riPv2Entry.setNextHopAddress(0);
			ripHandler.addEntry(riPv2Entry);
			routeTable.insert(iface.getIpAddress(), 0, iface.getSubnetMask(), iface);
		}
		logger.log(Level.DEBUG, "Added direct subnets");
		logger.log(Level.DEBUG, "RIPv2 Entries: " + ripHandler.getEntries().toString());
		logger.log(Level.DEBUG, "RouteTable:\n" + routeTable.toString());

		// send RIP request on all ports
		splitHorizon(RIPv2.COMMAND_REQUEST, new ArrayList<>());

		// schedule periodic updates
		logger.log(Level.DEBUG, "Scheduling periodic updates");
		this.executor.scheduleAtFixedRate(this::sendRIPv2Update, 0, 10, TimeUnit.SECONDS);
	}
	/**
	 * Load a new routing table from a file.
	 * @param routeTableFile the name of the file containing the routing table
	 */
	public void loadRouteTable(String routeTableFile)
	{
		if (!routeTable.load(routeTableFile, this))
		{
			System.err.println("Error setting up routing table from file "
					+ routeTableFile);
			System.exit(1);
		}
		
		System.out.println("Loaded static route table");
		System.out.println("-------------------------------------------------");
		System.out.print(this.routeTable.toString());
		System.out.println("-------------------------------------------------");
	}
	
	/**
	 * Load a new ARP cache from a file.
	 * @param arpCacheFile the name of the file containing the ARP cache
	 */
	public void loadArpCache(String arpCacheFile)
	{
		if (!arpCache.load(arpCacheFile))
		{
			System.err.println("Error setting up ARP cache from file "
					+ arpCacheFile);
			System.exit(1);
		}
		
		System.out.println("Loaded static ARP cache");
		System.out.println("----------------------------------");
		System.out.print(this.arpCache.toString());
		System.out.println("----------------------------------");
	}

	private void handleRIPv2Request(int sourceIP, MACAddress sourceMAC, Iface inIface) {
		logger.log(Level.DEBUG, "Responding to RIPv2 request from: "
			+ IPv4.fromIPv4Address(sourceIP));
		this.sendPacket(ripHandler.getRIPv2Payload(RIPv2.COMMAND_RESPONSE,
			inIface.getMacAddress(), sourceMAC,
			inIface.getIpAddress(), sourceIP), inIface);
	}

	private void syncRouteTable(Iface inIface) {
		// sync RouteTable
		logger.log(Level.DEBUG, "sync RouteTable with RIPv2 entries");
		for (RIPv2Entry ripv2Entry: ripHandler.getEntries()) {
			logger.log(Level.DEBUG, "\t" + ripv2Entry);
			RouteEntry routeEntry = routeTable.lookup(ripv2Entry.getAddress());
			if (routeEntry == null) {
				logger.log(Level.DEBUG, "\tnot in RouteTable");
				if (ripv2Entry.getMetric() != RIPv2Entry.INFINITY) {
					routeTable.insert(
						ripv2Entry.getAddress(), ripv2Entry.getNextHopAddress(),
						ripv2Entry.getSubnetMask(), inIface);
					logger.log(Level.DEBUG, "\tadded entry to RouteTable");
				}
			} else if (ripv2Entry.getMetric() == RIPv2Entry.INFINITY) {
				routeTable.remove(ripv2Entry.getAddress(), ripv2Entry.getSubnetMask());
				logger.log(Level.DEBUG, "\tremoved entry from RouteTable");
			} else if (Integer.compareUnsigned(
				routeEntry.getGatewayAddress(),
				ripv2Entry.getNextHopAddress()) != 0) {
				routeTable.update(
					ripv2Entry.getAddress(), ripv2Entry.getSubnetMask(),
					ripv2Entry.getNextHopAddress(), inIface);
				logger.log(Level.DEBUG, "\tupdated RouteTable entry");
			}
		}
		logger.log(Level.DEBUG,
	"Routing table after updates:\n" + routeTable.toString());
	}

	private void handleRIPv2Response(int sourceIP,
									 List<RIPv2Entry> sourceEntries, Iface inIface) {

		logger.log(Level.DEBUG, "Received RIPv2 response from: "
			+ IPv4.fromIPv4Address(sourceIP));

		// merge RIP entries
		logger.log(Level.DEBUG, "Merging RIPv2 entries");
		boolean changed = ripHandler.mergeRIPv2Entries(sourceEntries, sourceIP);
		logger.log(Level.DEBUG, "RIPv2 entries after merging: ");
		for (RIPv2Entry entry : ripHandler.getEntries()) {
			logger.log(Level.DEBUG, "\t" + entry);
		}

		// sync RouteTable with RIPv2 entries
		syncRouteTable(inIface);

		// broadcast RIP information if needed
		if (changed) {
			splitHorizon(RIPv2.COMMAND_RESPONSE, Collections.singletonList(inIface));
		}
	}

	private void handleRIPv2Packet(Ethernet etherPacket, Iface inIface) {

		logger.log(Level.DEBUG, "Handling RIPv2 packet");
		IPv4 ipv4Packet = (IPv4)etherPacket.getPayload();

		if (ipv4Packet.getProtocol() != IPv4.PROTOCOL_UDP) {
			// not an RIP packet, return
			return;
		}

		UDP udpRequest = (UDP) ipv4Packet.getPayload();
		if (udpRequest.getDestinationPort() != UDP.RIP_PORT) {
			// drop malformed RIP packet
			return;
		}

		// handle RIP packet
		RIPv2 ripPacket = (RIPv2) udpRequest.getPayload();
		if (ripPacket.getCommand() == RIPv2.COMMAND_REQUEST) {
			handleRIPv2Request(ipv4Packet.getSourceAddress(),
				etherPacket.getSourceMAC(), inIface);
		} else {
			handleRIPv2Response(ipv4Packet.getSourceAddress(),
				ripPacket.getEntries(), inIface);
		}
	}

	/**
	 * Handle an Ethernet packet received on a specific interface.
	 * @param etherPacket the Ethernet packet that was received
	 * @param inIface the interface on which the packet was received
	 */
	public void handlePacket(Ethernet etherPacket, Iface inIface)
	{

		if (etherPacket.getEtherType() != Ethernet.TYPE_IPv4) {
			logger.log(Level.DEBUG, "packet is not IPv4");
			// drop it
			return;
		}

		IPv4 ipv4Packet = (IPv4)etherPacket.getPayload();
		if (usingRIPv2 && ipv4Packet.getProtocol() == IPv4.PROTOCOL_UDP) {
			handleRIPv2Packet(etherPacket, inIface);
			return;
		}

		// check if checksum is valid
		short originalChecksum = ipv4Packet.getChecksum();

		ipv4Packet.resetChecksum();
		ipv4Packet.serialize();
		short calculatedChecksum = ipv4Packet.getChecksum();

		if (Short.compareUnsigned(originalChecksum, calculatedChecksum) != 0) {
			logger.log(Level.DEBUG, "checksum mismatch");
			logger.log(Level.DEBUG, "original: " + originalChecksum);
			logger.log(Level.DEBUG, "calculated: " + calculatedChecksum);
			// drop it
			return;
		}

		// update TTL
		ipv4Packet.setTtl((byte)((int)ipv4Packet.getTtl() - 1));
		if (ipv4Packet.getTtl() == 0) {
			logger.log(Level.DEBUG, "TTL expired");
			// drop expired packet
			return;
		}

		// update checksum
		ipv4Packet.resetChecksum();
		ipv4Packet.serialize();

		logger.log(Level.DEBUG,"Ipv4 packet destination address: "
				+ IPv4.fromIPv4Address(ipv4Packet.getDestinationAddress()));

		// check if packet is meant for this router
		for (Iface iface : this.getInterfaces().values()) {
			if (Integer.compareUnsigned(
					iface.getIpAddress(), ipv4Packet.getDestinationAddress()) == 0) {
				logger.log(Level.DEBUG, "packet is meant for this router");
				// drop it
				return;
			}
		}

		// else forward to best router
		RouteEntry bestEntry = routeTable.lookup(ipv4Packet.getDestinationAddress());
		if (bestEntry == null) {
			logger.log(Level.DEBUG, "no route found");
			// drop it
			return;
		}

		// get next hop gateway address, mac address
		int nextHop = bestEntry.getGatewayAddress();
		if (nextHop == 0) {
			logger.log(Level.DEBUG, "terminal router reached");
			nextHop = ipv4Packet.getDestinationAddress();
		}

		ArpEntry arpEntry = arpCache.lookup(nextHop);
		if (arpEntry == null) {
			logger.log(Level.DEBUG, "no ARP entry found");
			// drop it
			return;
		}
		MACAddress nextHopMac = arpEntry.getMac();
		Iface outIface = bestEntry.getInterface();
		if (outIface == null) {
			logger.log(Level.DEBUG, "no interface found");
			// shouldn't happen, drop it
			return;
		}
		if (outIface.getMacAddress() == null) {
			logger.log(Level.DEBUG, "no MAC address found for interface");
			// shouldn't happen, drop it
			return;
		}

		double rand = Math.random();
		// dropping a packet with 5% probability
		if (rand < 0.05) {
			System.out.println("Randomly dropping a packet");
			return;
		}

		// forward to next router
		try {
			logger.log(Level.DEBUG, "routing packet to: "
					+ IPv4.fromIPv4Address(outIface.getIpAddress()));
			etherPacket.setDestinationMACAddress(nextHopMac.toString());
			etherPacket.setSourceMACAddress(outIface.getMacAddress().toString());
			this.sendPacket(etherPacket, outIface);
		} catch (Exception e) {
			logger.log(Level.DEBUG, Arrays.toString(e.getStackTrace()));
		}
	}
}
