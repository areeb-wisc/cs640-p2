package edu.wisc.cs.sdn.vnet.tcp;

import net.floodlightcontroller.packet.IPacket;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class TCPpacket implements IPacket {

    // Header fields
    private int sequenceNumber;
    private int ackNumber;
    private long timestamp;
    private int lengthAndFlags; // 3-bit flags + 29-bit length
    private short checksum;
    private byte[] data;

    // Packet metadata
    private IPacket parent;
    private IPacket payload;

    // Flags
    private static final int SYN_FLAG = 0b100; // Bit 2 (3rd LSB)
    private static final int FIN_FLAG = 0b010; // Bit 1 (2nd LSB)
    private static final int ACK_FLAG = 0b001; // Bit 1 (1st LSB)

    public void setSequenceNumber(int seq) {
        sequenceNumber = seq;
    }

    public void setAckNumber(int ack) {
        ackNumber = ack;
    }

    public void setTimestamp(long ts) {
        timestamp = ts;
    }

    // Set flags in LSB of lengthAndFlags
    public void setFlags(boolean syn, boolean ack, boolean fin) {
        int flags =
                (syn ? SYN_FLAG : 0) |
                (ack ? ACK_FLAG : 0) |
                (fin ? FIN_FLAG : 0);

        // Preserve upper 29 bits
        lengthAndFlags = (lengthAndFlags & 0xFFFFFFF8) | flags;
    }

    // Set length in upper 29 bits
    public void setDataLength(int len) {
        // Shift left 3, preserve flags
        lengthAndFlags = (len << 3) | (lengthAndFlags & 0x00000007);
    }

    // Get length from upper 29 bits
    public int getDataLength() {
        return (lengthAndFlags >>> 3) & 0x1FFFFFFF;
    }

    @Override
    public IPacket getPayload() { return payload; }

    @Override
    public IPacket setPayload(IPacket payload) {
        this.payload = payload;
        return this;
    }

    @Override
    public IPacket getParent() { return parent; }

    @Override
    public IPacket setParent(IPacket parent) {
        this.parent = parent;
        return this;
    }

    @Override
    public void resetChecksum() {
        checksum = 0;
        if (parent != null) parent.resetChecksum();
    }

    @Override
    public byte[] serialize() {
        // Serialize payload first
        byte[] payloadData = payload != null ? payload.serialize() : new byte[0];
        data = payloadData;
        setDataLength(data.length);

        ByteBuffer bb = ByteBuffer.allocate(22 + data.length);

        // Header fields
        bb.putInt(sequenceNumber)
                .putInt(ackNumber)
                .putLong(timestamp)
                .putInt(lengthAndFlags)
                .putShort((short) 0); // Placeholder for checksum

        // Data payload
        if (data.length > 0) bb.put(data);

        // Compute checksum
        byte[] packetBytes = bb.array();
        checksum = calculateChecksum(packetBytes);
        bb.putShort(18, checksum); // Update checksum field

        return packetBytes;
    }

    @Override
    public IPacket deserialize(byte[] data, int offset, int length) {
        ByteBuffer bb = ByteBuffer.wrap(data, offset, length);

        sequenceNumber = bb.getInt();
        ackNumber = bb.getInt();
        timestamp = bb.getLong();
        lengthAndFlags = bb.getInt();
        checksum = bb.getShort();

        // Extract data payload
        int dataLength = lengthAndFlags & 0x1FFFFFFF;
        if (dataLength > 0) {
            this.data = new byte[dataLength];
            bb.get(this.data, 0, dataLength);
        }

        // Verify checksum
        if (calculateChecksum(data, offset, length) != checksum) {
            throw new RuntimeException("Checksum verification failed");
        }

        return this;
    }

    private short calculateChecksum(byte[] data) {
        return calculateChecksum(data, 0, data.length);
    }

    private short calculateChecksum(byte[] data, int offset, int length) {
        int sum = 0;
        int i = 0;

        // Process 16-bit words
        while (i < length - 1) {
            sum += ((data[offset + i] & 0xFF) << 8) | (data[offset + i + 1] & 0xFF);
            i += 2;

            // Handle carryover
            if ((sum & 0xFFFF0000) != 0) {
                sum &= 0xFFFF;
                sum++;
            }
        }

        // Handle odd-length case
        if (i < length) {
            sum += (data[offset + i] & 0xFF) << 8;
            if ((sum & 0xFFFF0000) != 0) {
                sum &= 0xFFFF;
                sum++;
            }
        }

        return (short) ~sum;
    }

    @Override
    public Object clone() {
        try {
            TCPpacket clone = (TCPpacket) super.clone();
            if (data != null) clone.data = Arrays.copyOf(data, data.length);
            if (payload != null) clone.payload = (IPacket) payload.clone();
            return clone;
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException("Clone not supported", e);
        }
    }

    // Helper methods for flags
    public boolean isSYN() { return (lengthAndFlags & SYN_FLAG) != 0; }
    public boolean isACK() { return (lengthAndFlags & ACK_FLAG) != 0; }
    public boolean isFIN() { return (lengthAndFlags & FIN_FLAG) != 0; }
}
