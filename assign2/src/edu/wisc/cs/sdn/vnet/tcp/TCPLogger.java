package edu.wisc.cs.sdn.vnet.tcp;

import java.text.DecimalFormat;

public class TCPLogger {
    private final long startTime = System.nanoTime();
    private final DecimalFormat timeFormat = new DecimalFormat("0.000");

    private long dataTransferred = 0;
    private int packetsSent = 0;
    private int packetsReceived = 0;
    private int outOfSequence = 0;
    private int checksumErrors = 0;
    private int retransmissions = 0;
    private int duplicateAcks = 0;

    public void logSend(TCPpacket packet) {
        logEvent(true, packet);
        packetsSent++;
    }

    public void logReceive(TCPpacket packet) {
        logEvent(false, packet);
        packetsReceived++;
    }

    private void logEvent(boolean isSend, TCPpacket packet) {
        double time = (System.nanoTime() - startTime) / 1e9;
        String flags = getFlags(packet);

        System.out.printf("%s %s %s %d %d %d%n",
                isSend ? "snd" : "rcv",
                timeFormat.format(time),
                flags,
                packet.getSequenceNumber(),
                packet.getDataLength(),
                packet.getAckNumber()
        );
    }

    private String getFlags(TCPpacket packet) {
        StringBuilder sb = new StringBuilder();
        sb.append(packet.isSYN() ? "S" : "-");
        sb.append(packet.isACK() ? "A" : "-");
        sb.append(packet.isFIN() ? "F" : "-");
        sb.append(packet.getData() != null && packet.getData().length > 0 ? "D" : "-");
        return sb.toString();
    }

    public void printStatistics() {
        System.out.println("Amount of Data transferred/received: " + dataTransferred + " bytes");
        System.out.println("Number of packets sent: " + packetsSent);
        System.out.println("Number of packets received: " + packetsReceived);
        System.out.println("Number of out-of-sequence packets discarded: " + outOfSequence);
        System.out.println("Number of packets discarded due to incorrect checksum: " + checksumErrors);
        System.out.println("Number of retransmissions: " + retransmissions);
        System.out.println("Number of duplicate acknowledgements: " + duplicateAcks);
    }

    public void addDataReceived(int bytes) { dataTransferred += bytes; }
    public void incrementRetransmissions() { retransmissions++; }
    public void incrementDuplicateAcks() { duplicateAcks++; }
    public void incrementOutOfSequence() { outOfSequence++; }
    public void incrementChecksumErrors() { checksumErrors++; }
    public int getDuplicateAckCount() { return duplicateAcks; }
    public void logError(String msg) { System.err.println("ERROR: " + msg); }
    public void log(String msg) { System.out.println("INFO: " + msg); }
}
