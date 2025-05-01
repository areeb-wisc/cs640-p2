package edu.wisc.cs.sdn.vnet.tcp;

import java.net.*;
import java.io.*;
import java.util.*;

public class TCPend {

    public static void main(String[] args) {
        try {
            Map<String, String> params = parseArgs(args);
            if (params.containsKey("-s")) {
                runSender(params);
            } else {
                runReceiver(params);
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    private static void runSender(Map<String, String> params) throws Exception {
        int localPort = Integer.parseInt(params.get("-p"));
        InetAddress receiverIP = InetAddress.getByName(params.get("-s"));
        int receiverPort = Integer.parseInt(params.get("-a"));
        String fileName = params.get("-f");
        int mtu = Integer.parseInt(params.get("-m"));
        int sws = Integer.parseInt(params.get("-c"));

        DatagramSocket socket = new DatagramSocket(localPort);
        FileInputStream fileStream = new FileInputStream(fileName);
        TCPMetrics metrics = new TCPMetrics();

        new Sender(socket, receiverIP, receiverPort, fileStream, mtu, sws, metrics).start();
    }

    private static void runReceiver(Map<String, String> params) throws Exception {
        int port = Integer.parseInt(params.get("-p"));
        String fileName = params.get("-f");
        int mtu = Integer.parseInt(params.get("-m"));
        int sws = Integer.parseInt(params.get("-c"));

        DatagramSocket socket = new DatagramSocket(port);
        FileOutputStream fileStream = new FileOutputStream(fileName);
        TCPMetrics metrics = new TCPMetrics();

        new Receiver(socket, fileStream, mtu, sws, metrics).start();
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("-")) {
                params.put(args[i], args[++i]);
            }
        }
        validateParams(params);
        return params;
    }

    private static void validateParams(Map<String, String> params) {
        if (!params.containsKey("-p") || !params.containsKey("-f") ||
                !params.containsKey("-m") || !params.containsKey("-c")) {
            throw new IllegalArgumentException("Missing required parameters");
        }
        if (params.containsKey("-s") != params.containsKey("-a")) {
            throw new IllegalArgumentException("Sender requires both -s and -a");
        }
    }
}
