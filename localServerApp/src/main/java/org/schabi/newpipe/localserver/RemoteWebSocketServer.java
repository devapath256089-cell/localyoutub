package org.schabi.newpipe.localserver;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RemoteWebSocketServer extends WebSocketServer {
    
    private final Set<WebSocket> connections = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public RemoteWebSocketServer(int port) {
        super(new InetSocketAddress(port));
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        // Respect the Web Access PIN: drop sockets from non-local clients that did
        // not authenticate over HTTP (browsers automatically carry the session
        // cookie on the WS upgrade because cookies are not scoped by port).
        if (LocalHttpServer.isPinAuthActive()) {
            boolean local = false;
            try {
                local = LocalHttpServer.isLocalRequest(conn.getRemoteSocketAddress().getAddress());
            } catch (Exception ignored) {
            }
            if (!local && !LocalHttpServer.isValidSessionToken(
                    LocalHttpServer.extractSessionToken(handshake.getFieldValue("Cookie")))) {
                LocalHttpServer.log("WebSocket connection rejected (PIN required): " + conn.getRemoteSocketAddress());
                conn.close(4401, "PIN required");
                return;
            }
        }
        connections.add(conn);
        LocalHttpServer.log("WebSocket client connected: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        connections.remove(conn);
        LocalHttpServer.log("WebSocket client disconnected: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        if (message != null && message.startsWith("register_client:")) {
            String clientName = message.substring("register_client:".length());
            conn.setAttachment(clientName);
            LocalHttpServer.log("Registered client: " + clientName + " for IP " + conn.getRemoteSocketAddress());
            return;
        }

        // Forward the message to LocalHttpServer pending commands queue
        LocalHttpServer.addPendingCommand(message);
        
        // Broadcast immediately to other connected clients (like the TV browser)
        broadcastCommand(message, conn);
    }

    public Set<WebSocket> getConnections() {
        return connections;
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        LocalHttpServer.log("WebSocket error: " + ex.getMessage());
        if (conn != null) {
            connections.remove(conn);
        }
    }

    @Override
    public void onStart() {
        LocalHttpServer.log("WebSocket server started on port " + getPort());
    }
    
    public void broadcastCommand(String command) {
        broadcastCommand(command, null);
    }
    
    public void broadcastCommand(String command, WebSocket excludeConn) {
        for (WebSocket conn : connections) {
            if (conn != excludeConn && conn.isOpen()) {
                try {
                    conn.send(command);
                } catch (Exception e) {
                    LocalHttpServer.log("Failed to send WS command: " + e.getMessage());
                }
            }
        }
    }
}
