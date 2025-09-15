package com.yusssss.vcmail.core.utilities.rtp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.function.Consumer;

public class RtpListener implements Runnable {

    private final Logger logger = LoggerFactory.getLogger(RtpListener.class);
    private final String callId;
    private final int port;
    private DatagramSocket socket;
    private volatile boolean isListening = false;
    private Consumer<byte[]> onAudioData;
    private final Thread listenerThread;

    public RtpListener(String callId, int port) {
        this.callId = callId;
        this.port = port;
        this.listenerThread = new Thread(this);
        this.listenerThread.setName("RtpListener-" + callId);
        this.listenerThread.setDaemon(true);
    }

    public int getPort() {
        return this.port;
    }

    public void onAudioData(Consumer<byte[]> callback) {
        this.onAudioData = callback;
    }

    public void start() {
        if (!isListening) {
            isListening = true;
            listenerThread.start();
        }
    }

    @Override
    public void run() {
        try {
            socket = new DatagramSocket(port);
            byte[] buffer = new byte[1024];
            logger.info("[{}] RTP Listener started on UDP port {}", callId, port);

            while (isListening) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                int payloadOffset = 12;
                int payloadLength = packet.getLength() - payloadOffset;

                if (payloadLength > 0 && onAudioData != null) {
                    byte[] audioData = new byte[payloadLength];
                    System.arraycopy(packet.getData(), packet.getOffset() + payloadOffset, audioData, 0, payloadLength);

                    onAudioData.accept(audioData);
                }
            }
        } catch (Exception e) {
            if (isListening) {
                logger.error("[{}] RTP Listener crashed", callId, e);
            }
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
            logger.info("[{}] RTP Listener stopped on port {}", callId, port);
        }
    }

    public void stop() {
        logger.info("[{}] Stopping RTP Listener on port {}...", callId, port);
        isListening = false;
        if (socket != null) {
            socket.close();
        }
    }
}