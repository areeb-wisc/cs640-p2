package edu.wisc.cs.sdn.vnet.sw;

import net.floodlightcontroller.packet.Ethernet;
import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;
import net.floodlightcontroller.packet.MACAddress;

import java.util.Map;

/**
 * @author Aaron Gember-Jacobson
 */
public class Switch extends Device
{

	private SwitchTable switchTable;
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
//		System.out.println("*** -> Received packet: " +
//				etherPacket.toString().replace("\n", "\n\t"));

		System.out.println("Source: " + etherPacket.getSourceMAC());
		System.out.println("Destination: " + etherPacket.getDestinationMAC());

		// check if source mac exists, if not add it
		try {
			MACAddress sourceMac = etherPacket.getSourceMAC();
			if (!switchTable.hasEntry(sourceMac)) {
				System.out.println("Adding source mac: " + sourceMac);
				switchTable.addEntry(sourceMac, inIface);
			} else {
				System.out.println("Updating source mac: " + sourceMac);
				switchTable.updateEntry(sourceMac);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

		try {
			MACAddress destMac = etherPacket.getDestinationMAC();
			// if destination mac exists, forward to it, else broadcast
			if (switchTable.hasEntry(destMac)) {
				System.out.println("Forwarding directly to destination mac: " + destMac);
				Iface outIface = switchTable.getIface(destMac);
				if (outIface == null) {
					System.out.println("NULL outIface for: " + destMac);
				} else {
					this.sendPacket(etherPacket, outIface);
				}
			} else {
				System.out.println("Broadcasting");
				for (Map.Entry<String, Iface> entry : this.getInterfaces().entrySet()) {
					Iface outIface = entry.getValue();
					if (outIface == null) {
						System.out.println("NULL outIface for: " + entry.getKey());
						continue;
					}
					if (!outIface.getName().equals(inIface.getName())) {
						this.sendPacket(etherPacket, outIface);
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
