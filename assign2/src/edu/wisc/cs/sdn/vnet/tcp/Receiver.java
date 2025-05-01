package edu.wisc.cs.sdn.vnet.tcp;

import java.net.*;
import java.io.*;
import java.util.*;

public class Receiver {
    private final DatagramSocket socket;
    private final FileOutputStream fileStream;
    private final int mtu;
    private final int sws;
    private final TCPMetrics metrics;
    private final TreeMap<Integer, byte[]> buffer = new TreeMap<>();
    private int sequenceNum = 0;
    private int expectedAckNum = 0;
    private InetAddress senderAddress;
    private int senderPort;
    private boolean running = true;

    public Receiver(DatagramSocket socket, FileOutputStream fileStream,
                    int mtu, int sws, TCPMetrics metrics) {
        this.socket = socket;
        this.fileStream = fileStream;
        this.mtu = mtu;
        this.sws = sws;
        this.metrics = metrics;
    }

    public void start() throws Exception {
        establishConnection();
        receiveData();
    }

    private void establishConnection() throws IOException {
        TCPpacket syn = receivePacket();
        if (!syn.isSYN()) throw new IOException("Invalid SYN");

        TCPpacket synAck = new TCPpacket();
        synAck.setSYN(true);
        synAck.setACK(true);
        synAck.setSequenceNumber(sequenceNum);
        synAck.setAckNumber(syn.getSequenceNumber() + 1);
        synAck.setTimestamp(syn.getTimestamp());
        sendPacket(synAck);
    }

    private void receiveData() throws IOException {
        while (running) {
            TCPpacket packet = receivePacket();
            if (packet.isFIN()) {
                terminateConnection(packet);
                break;
            }
            if (!validateChecksum(packet)) {
                continue;
            }
            processPacket(packet);
        }
    }

    private void processPacket(TCPpacket packet) throws IOException {
        int seq = packet.getSequenceNumber();
        byte[] data = packet.getData();

        if (seq == expectedAckNum) {
            deliverData(data);
            expectedAckNum += data.length;
            checkBuffer();
        } else if (seq > expectedAckNum) {
            buffer.put(seq, data);
            metrics.incrementOutOfSequence();
        }
        sendAck(packet.getTimestamp());
    }

    private void deliverData(byte[] data) throws IOException {
        fileStream.write(data);
        metrics.addDataReceived(data.length);
    }

    private void checkBuffer() throws IOException {
        while (!buffer.isEmpty() && buffer.firstKey() == expectedAckNum) {
            byte[] data = buffer.remove(expectedAckNum);
            deliverData(data);
            expectedAckNum += data.length;
        }
    }

    private void sendAck(long timestamp) throws IOException {
        TCPpacket ack = new TCPpacket();
        ack.setACK(true);
        ack.setAckNumber(expectedAckNum);
        ack.setTimestamp(timestamp);
        sendPacket(ack);
    }

    private void terminateConnection(TCPpacket fin) throws IOException {
        TCPpacket finAck = new TCPpacket();
        finAck.setACK(true);
        finAck.setFIN(true);
        finAck.setSequenceNumber(sequenceNum);
        finAck.setAckNumber(fin.getSequenceNumber() + 1);
        finAck.setTimestamp(fin.getTimestamp());
        sendPacket(finAck);

        // Set timeout to avoid hanging forever
        socket.setSoTimeout(5000);

        try {
            // Wait for final ACK
            TCPpacket finalAck = receivePacket();
            if (finalAck.isACK()) {
                running = false; // Signal threads to terminate
            }
        } catch (SocketTimeoutException e) {
            // Still terminate if timeout occurs
            running = false;
        } finally {
            fileStream.close();
            metrics.printStatistics();
            socket.close();
        }

    }

    private boolean validateChecksum(TCPpacket packet) {
        short receivedChecksum = packet.getChecksum();
        packet.resetChecksum();
        packet.serialize();
        short calculatedChecksum = packet.getChecksum();
        return receivedChecksum == calculatedChecksum;
    }

    private void sendPacket(TCPpacket packet) throws IOException {
        byte[] data = packet.serialize();
        DatagramPacket udpPacket = new DatagramPacket(
                data, data.length, senderAddress, senderPort);
        socket.send(udpPacket);
        metrics.logSend(packet);
    }

    private TCPpacket receivePacket() throws IOException {
        byte[] buffer = new byte[mtu + 24];
        DatagramPacket udpPacket = new DatagramPacket(buffer, buffer.length);
        socket.receive(udpPacket);
        senderAddress = udpPacket.getAddress();
        senderPort = udpPacket.getPort();
        TCPpacket packet = new TCPpacket();
        packet.deserialize(udpPacket.getData(), 0, udpPacket.getLength());
        metrics.logReceive(packet);
        if (!validateChecksum(packet)) {
            metrics.incrementChecksumErrors();
        }
        return packet;
    }
}
