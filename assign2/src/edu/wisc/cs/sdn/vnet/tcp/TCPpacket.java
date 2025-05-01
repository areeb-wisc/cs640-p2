package edu.wisc.cs.sdn.vnet.tcp;

import net.floodlightcontroller.packet.BasePacket;
import net.floodlightcontroller.packet.IPacket;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class TCPpacket extends BasePacket {

    private int sequenceNumber;
    private int ackNumber;
    private long timestamp;
    private int lengthAndFlags;
    private short checksum;
    private byte[] data;

    private static final int SYN_FLAG = 0b100;
    private static final int FIN_FLAG = 0b010;
    private static final int ACK_FLAG = 0b001;

    @Override
    public byte[] serialize() {
        int dataLength = (data != null) ? data.length : 0;
        setDataLength(dataLength);

        ByteBuffer bb = ByteBuffer.allocate(24 + dataLength);
        bb.putInt(sequenceNumber)
                .putInt(ackNumber)
                .putLong(timestamp)
                .putInt(lengthAndFlags)
                .putShort((short)0) // 16-bit padding (zeros)
                .putShort((short)0); // Placeholder for checksum

        if (data != null) bb.put(data);

        byte[] packetBytes = bb.array();
        checksum = calculateChecksum(packetBytes);
        bb.putShort(22, checksum);

        return bb.array();
    }

    @Override
    public IPacket deserialize(byte[] data, int offset, int length) {
        ByteBuffer bb = ByteBuffer.wrap(data, offset, length);
        sequenceNumber = bb.getInt();
        ackNumber = bb.getInt();
        timestamp = bb.getLong();
        lengthAndFlags = bb.getInt();
        bb.getShort(); // Skip 16-bit padding
        checksum = bb.getShort();

        int dataLength = getDataLength();
        if (dataLength > 0) {
            this.data = new byte[dataLength];
            bb.get(this.data);
        }

        byte[] packetCopy = Arrays.copyOfRange(data, offset, offset + length);
        Arrays.fill(packetCopy, 18, 20, (byte)0);
        if (calculateChecksum(packetCopy) != checksum) {
            throw new RuntimeException("Checksum verification failed");
        }

        return this;
    }

    private short calculateChecksum(byte[] data) {
        int sum = 0;
        for (int i = 0; i < data.length; i += 2) {
            int word = ((data[i] & 0xFF) << 8);
            if (i + 1 < data.length) word |= (data[i + 1] & 0xFF);
            sum += word;
            sum = (sum & 0xFFFF) + (sum >> 16); // Carry wrap-around
        }
        return (short) ~sum;
    }

    // Getters and setters
    public void setSequenceNumber(int seq) { sequenceNumber = seq; }
    public int getSequenceNumber() { return sequenceNumber; }
    public void setAckNumber(int ack) { ackNumber = ack; }
    public int getAckNumber() { return ackNumber; }
    public void setTimestamp(long ts) { timestamp = ts; }
    public long getTimestamp() { return timestamp; }
    public void setData(byte[] data) { this.data = data; }
    public byte[] getData() { return data; }
    public void setDataLength(int len) { lengthAndFlags = (len << 3) | (lengthAndFlags & 0x7); }
    public int getDataLength() { return (lengthAndFlags >>> 3) & 0x1FFFFFFF; }

    // Flag setters
    public void setSYN(boolean syn) {
        if (syn) lengthAndFlags |= SYN_FLAG;
        else lengthAndFlags &= ~SYN_FLAG;
    }
    public void setACK(boolean ack) {
        if (ack) lengthAndFlags |= ACK_FLAG;
        else lengthAndFlags &= ~ACK_FLAG;
    }
    public void setFIN(boolean fin) {
        if (fin) lengthAndFlags |= FIN_FLAG;
        else lengthAndFlags &= ~FIN_FLAG;
    }

    // Flag getters
    public boolean isSYN() { return (lengthAndFlags & SYN_FLAG) != 0; }
    public boolean isACK() { return (lengthAndFlags & ACK_FLAG) != 0; }
    public boolean isFIN() { return (lengthAndFlags & FIN_FLAG) != 0; }

    public short getChecksum() { return checksum; }

    @Override
    public void resetChecksum() {
        this.checksum = ((short)0);
        super.resetChecksum();
    }


    @Override
    public Object clone() {
        TCPpacket clone = (TCPpacket) super.clone();
        if (this.data != null) {
            clone.data = Arrays.copyOf(this.data, this.data.length);
        }
        return clone;
    }
}
