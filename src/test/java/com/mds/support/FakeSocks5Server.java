package com.mds.support;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Minimal SOCKS5 server for tests: accepts the no-auth handshake, records each CONNECT
 * destination, then answers "connection refused" so nothing leaves the machine.
 */
public final class FakeSocks5Server implements AutoCloseable {

    private final ServerSocket server;
    private final List<String> destinations = new CopyOnWriteArrayList<>();
    private final Thread acceptor;

    public FakeSocks5Server() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        acceptor = Thread.ofVirtual().start(this::acceptLoop);
    }

    public int port() {
        return server.getLocalPort();
    }

    /** {@code host:port} of every CONNECT request received, in arrival order. */
    public List<String> destinations() {
        return List.copyOf(destinations);
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                Thread.ofVirtual().start(() -> handle(client));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void handle(Socket client) {
        try (client) {
            DataInputStream in = new DataInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();
            if (in.readUnsignedByte() != 5) {
                return; // not SOCKS5
            }
            in.skipNBytes(in.readUnsignedByte()); // offered auth methods
            out.write(new byte[] {5, 0}); // no authentication
            out.flush();

            in.readUnsignedByte(); // version
            in.readUnsignedByte(); // command (CONNECT)
            in.readUnsignedByte(); // reserved
            String host = switch (in.readUnsignedByte()) {
                case 1 -> InetAddress.getByAddress(in.readNBytes(4)).getHostAddress();
                case 3 -> new String(in.readNBytes(in.readUnsignedByte()));
                case 4 -> InetAddress.getByAddress(in.readNBytes(16)).getHostAddress();
                default -> "?";
            };
            int port = in.readUnsignedShort();
            destinations.add(host + ":" + port);
            out.write(new byte[] {5, 5, 0, 1, 0, 0, 0, 0, 0, 0}); // connection refused
            out.flush();
        } catch (IOException e) {
            // client went away; nothing to record
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
        acceptor.interrupt();
    }
}
