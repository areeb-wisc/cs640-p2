package edu.wisc.cs.sdn.vnet.tcp;

import edu.wisc.cs.sdn.vnet.logging.Level;
import edu.wisc.cs.sdn.vnet.logging.Logger;

import java.net.*;
import java.io.*;
import java.util.Arrays;

public class Sender {
    private final DatagramSocket socket;
    private final InetAddress receiverIP;
    private final int receiverPort;
    private final FileInputStream fileStream;
    private final int mtu;
    private final int sws;
    private final TCPMetrics metrics;
    private final RetransmissionManager retransmitter;
    private boolean running = true;
    private final Logger logger = new Logger();

    private int baseSeq = 0;
    private int nextSeq = 0;
    private int lastAck = -1;
    private int duplicateAckCount = 0;

    public Sender(DatagramSocket socket, InetAddress receiverIP, int receiverPort,
                  FileInputStream fileStream, int mtu, int sws, TCPMetrics metrics) {
        this.socket = socket;
        this.receiverIP = receiverIP;
        this.receiverPort = receiverPort;
        this.fileStream = fileStream;
        this.mtu = mtu;
        this.sws = sws;
        this.metrics = metrics;
        this.retransmitter = new RetransmissionManager();
    }

    public void start() throws Exception {
        try {
            if (!establishConnection()) {
                logger.log(Level.DEBUG,"Connection failed");
                return;
            }
            new Thread(this::ackListener).start();
            transferData();
            terminateConnection();
        } catch (Exception e) {
            logger.log(Level.ERROR, e.getMessage());
        } finally {
            running = false;
            retransmitter.shutdown();
            metrics.printStatistics();
        }
    }

    private void ackListener() {
        while (running) {
            try {
                TCPpacket ack = receivePacket(); // Blocks until ACK arrives
                handleAck(ack); // Updates window & retransmission state
            } catch (IOException e) {
                logger.log(Level.DEBUG,"ACK reception failed: " + e.getMessage());
            }
        }
    }

    private boolean establishConnection() throws IOException {
        TCPpacket syn = new TCPpacket();
        syn.setSYN(true);
        syn.setSequenceNumber(0);

        for (int retry = 0; retry < RetransmissionManager.MAX_RETRIES; retry++) {
            sendPacket(syn);
            try {
                socket.setSoTimeout(5000);
                TCPpacket synAck = receivePacket();
                if (synAck.isSYN() && synAck.isACK()) {
                    sendAck(synAck);
                    return true;
                }
            } catch (SocketTimeoutException e) {
                logger.log(Level.DEBUG, "SYN timeout retry " + (retry + 1));
            }
        }
        return false;
    }

    private void transferData() throws Exception {
        byte[] buffer = new byte[mtu];
        int bytesRead;

        while (running && ((bytesRead = fileStream.read(buffer)) != -1)) {
            while (running && (nextSeq - baseSeq >= sws * mtu)) {
                Thread.sleep(10);
            }
            byte[] chunk = Arrays.copyOf(buffer, bytesRead);
            TCPpacket packet = new TCPpacket();
            packet.setData(chunk);
            packet.setSequenceNumber(nextSeq);
            packet.setACK(true);
            sendPacket(packet);
            metrics.addDataTransferred(packet.getData().length);
            nextSeq += bytesRead;
        }
    }

    private void terminateConnection() throws IOException {
        TCPpacket fin = new TCPpacket();
        fin.setFIN(true);
        fin.setSequenceNumber(nextSeq);
        sendPacket(fin);

        TCPpacket finAck = receivePacket();
        if (finAck.isFIN() && finAck.isACK()) {
            sendAck(finAck);
        }
    }

    private void sendPacket(TCPpacket packet) throws IOException {
        packet.setTimestamp(System.nanoTime());
        byte[] data = packet.serialize();
        DatagramPacket udpPacket = new DatagramPacket(
                data, data.length, receiverIP, receiverPort);

        retransmitter.scheduleRetransmission(
                packet.getSequenceNumber(),
                () -> resendPacket(packet));

        socket.send(udpPacket);
        metrics.logSend(packet);
    }

    public void resendPacket(TCPpacket packet) {
        try {
            metrics.incrementRetransmissions();
            packet.setTimestamp(System.nanoTime());
            byte[] data = packet.serialize();
            DatagramPacket udpPacket = new DatagramPacket(
                    data, data.length, receiverIP, receiverPort);
            socket.send(udpPacket);
            metrics.logSend(packet);
        } catch (IOException e) {
            logger.log(Level.DEBUG,"Retransmit failed: " + e.getMessage());
        }
    }

    private TCPpacket receivePacket() throws IOException {
        byte[] buffer = new byte[mtu + 24];
        DatagramPacket udpPacket = new DatagramPacket(buffer, buffer.length);
        socket.receive(udpPacket);

        TCPpacket packet = new TCPpacket();
        packet.deserialize(udpPacket.getData(), 0, udpPacket.getLength());
        metrics.logReceive(packet);

        if (packet.isACK()) {
            handleAck(packet);
        }
        return packet;
    }

    private void handleAck(TCPpacket ack) {
        int ackNum = ack.getAckNumber();
        if (ackNum > lastAck) {
            baseSeq = ackNum;
            retransmitter.cancelRetransmissionsBelow(ackNum);
            lastAck = ackNum;
            duplicateAckCount = 1;
        } else if (ackNum == lastAck) {
            duplicateAckCount++;
            metrics.incrementDuplicateAcks();
            if (duplicateAckCount >= 3) {
                retransmitter.forceRetransmit(baseSeq);
            }
        }

        // RTT update
        long rtt = System.nanoTime() - ack.getTimestamp();
        retransmitter.updateRTT(rtt);
    }

    // sendAck for handshake and FIN
    public void sendAck(TCPpacket received) throws IOException {
        TCPpacket ack = new TCPpacket();
        ack.setACK(true);
        ack.setSequenceNumber(received.getAckNumber());
        ack.setAckNumber(received.getSequenceNumber() + 1);
        sendPacket(ack);
    }
}
