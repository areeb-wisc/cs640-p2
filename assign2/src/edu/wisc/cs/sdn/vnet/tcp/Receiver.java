package edu.wisc.cs.sdn.vnet.tcp;

import java.net.*;
import java.io.*;
import java.util.*;

public class Receiver {
    private final DatagramSocket socket;
    private final FileOutputStream fileStream;
    private final int mtu;
    private final int sws;
    private final TCPMetrics logger;
    private final TreeMap<Integer, byte[]> buffer = new TreeMap<>();
    private int expectedSeq = 0;
    private InetAddress senderAddress;
    private int senderPort;

    public Receiver(DatagramSocket socket, FileOutputStream fileStream,
                    int mtu, int sws, TCPMetrics logger) {
        this.socket = socket;
        this.fileStream = fileStream;
        this.mtu = mtu;
        this.sws = sws;
        this.logger = logger;
    }

    public void start() throws Exception {
        establishConnection();
        receiveData();
        fileStream.close();
        logger.printStatistics();
    }

    private void establishConnection() throws IOException {
        TCPpacket syn = receivePacket();
        if (!syn.isSYN()) throw new IOException("Invalid SYN");

        TCPpacket synAck = new TCPpacket();
        synAck.setSYN(true);
        synAck.setACK(true);
        synAck.setAckNumber(syn.getSequenceNumber() + 1);
        sendPacket(synAck);

        TCPpacket ack = receivePacket();
        if (!ack.isACK()) throw new IOException("Invalid ACK");
    }

    private void receiveData() throws IOException {
        while (true) {
            TCPpacket packet = receivePacket();
            if (packet.isFIN()) {
                terminateConnection(packet);
                break;
            }
            if (!validateChecksum(packet)) {
                logger.incrementChecksumErrors();
                continue;
            }
            processPacket(packet);
        }
    }

    private void processPacket(TCPpacket packet) throws IOException {
        int seq = packet.getSequenceNumber();
        byte[] data = packet.getData();

        if (seq == expectedSeq) {
            deliverData(data);
            expectedSeq += data.length;
            checkBuffer();
        } else if (seq > expectedSeq) {
            buffer.put(seq, data);
            logger.incrementOutOfSequence();
        }
        sendAck();
    }

    private void deliverData(byte[] data) throws IOException {
        fileStream.write(data);
        logger.addDataReceived(data.length);
    }

    private void checkBuffer() throws IOException {
        while (!buffer.isEmpty() && buffer.firstKey() == expectedSeq) {
            byte[] data = buffer.remove(expectedSeq);
            deliverData(data);
            expectedSeq += data.length;
        }
    }

    private void sendAck() throws IOException {
        TCPpacket ack = new TCPpacket();
        ack.setACK(true);
        ack.setAckNumber(expectedSeq);
        sendPacket(ack);
    }

    private void terminateConnection(TCPpacket fin) throws IOException {
        TCPpacket finAck = new TCPpacket();
        finAck.setACK(true);
        finAck.setFIN(true);
        finAck.setAckNumber(fin.getSequenceNumber() + 1);
        sendPacket(finAck);

        TCPpacket finalAck = receivePacket();
        if (!finalAck.isACK()) {
            throw new IOException("Connection termination failed");
        }
    }

    private boolean validateChecksum(TCPpacket packet) {
        short receivedChecksum = packet.getChecksum();
        packet.setChecksum((short)0);
        short calculatedChecksum = packet.serialize() != null ? packet.calculateChecksum(packet.serialize()) : 0;
        return receivedChecksum == calculatedChecksum;
    }

    private void sendPacket(TCPpacket packet) throws IOException {
        packet.setTimestamp(System.nanoTime());
        byte[] data = packet.serialize();
        DatagramPacket udpPacket = new DatagramPacket(
                data, data.length, senderAddress, senderPort);
        socket.send(udpPacket);
        logger.logSend(packet);
    }

    private TCPpacket receivePacket() throws IOException {
        byte[] buffer = new byte[mtu + 24];
        DatagramPacket udpPacket = new DatagramPacket(buffer, buffer.length);
        socket.receive(udpPacket);
        senderAddress = udpPacket.getAddress();
        senderPort = udpPacket.getPort();
        TCPpacket packet = new TCPpacket();
        packet.deserialize(udpPacket.getData(), 0, udpPacket.getLength());
        logger.logReceive(packet);
        return packet;
    }
}
