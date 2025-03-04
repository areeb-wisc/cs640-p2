package edu.wisc.cs.sdn.vnet.sw;

import edu.wisc.cs.sdn.vnet.logging.Level;
import edu.wisc.cs.sdn.vnet.logging.Logger;
import net.floodlightcontroller.packet.Ethernet;
import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;
import net.floodlightcontroller.packet.MACAddress;

import java.util.Arrays;
import java.util.Map;

/**
 * @author Aaron Gember-Jacobson
 */
public class Switch extends Device
{

	private final SwitchTable switchTable;
	private static final Logger logger = new Logger();
	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Switch(String host, DumpFile logfile)
	{
		super(host,logfile);
		this.switchTable = new SwitchTable();
	}

	/**
	 * Handle an Ethernet packet received on a specific interface.
	 * @param etherPacket the Ethernet packet that was received
	 * @param inIface the interface on which the packet was received
	 */
	public void handlePacket(Ethernet etherPacket, Iface inIface)
	{
		System.out.println("*** -> Received packet: " +
				etherPacket.toString().replace("\n", "\n\t"));

		logger.log(Level.DEBUG, "Source: " + etherPacket.getSourceMAC());
		logger.log(Level.DEBUG, "Destination: " + etherPacket.getDestinationMAC());

		// check if source mac exists, if not add it
		try {
			MACAddress sourceMac = etherPacket.getSourceMAC();
			if (!switchTable.hasEntry(sourceMac)) {
				logger.log(Level.DEBUG, "Adding source mac: " + sourceMac);
				switchTable.addEntry(sourceMac, inIface);
			} else {
				logger.log(Level.DEBUG, "Updating source mac: " + sourceMac);
				switchTable.updateEntry(sourceMac);
			}
		} catch (Exception e) {
			logger.log(Level.ERROR, Arrays.toString(e.getStackTrace()));
		}

		try {
			MACAddress destMac = etherPacket.getDestinationMAC();
			// if destination mac exists, forward to it, else broadcast
			if (switchTable.hasEntry(destMac)) {
				logger.log(Level.DEBUG, "Forwarding directly to destination mac: " + destMac);
				Iface outIface = switchTable.getIface(destMac);
				if (outIface == null) {
					logger.log(Level.DEBUG, "NULL outIface for: " + destMac);
				} else {
					this.sendPacket(etherPacket, outIface);
				}
			} else {
				logger.log(Level.DEBUG, "Broadcasting");
				for (Map.Entry<String, Iface> entry : this.getInterfaces().entrySet()) {
					Iface outIface = entry.getValue();
					if (outIface == null) {
						logger.log(Level.DEBUG, "!!NULL outIface for: " + entry.getKey());
						continue;
					}
					if (!outIface.getName().equals(inIface.getName())) {
						this.sendPacket(etherPacket, outIface);
					}
				}
			}
		} catch (Exception e) {
			logger.log(Level.ERROR, Arrays.toString(e.getStackTrace()));
		}
	}
}
