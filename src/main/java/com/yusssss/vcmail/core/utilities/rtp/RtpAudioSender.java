package com.yusssss.vcmail.core.utilities.rtp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class RtpAudioSender {

    private static final Logger logger = LoggerFactory.getLogger(RtpAudioSender.class);

    private final Map<String, RtpSenderInstance> senders = new ConcurrentHashMap<>();

    public void createSender(String conversationId, String destinationHost, int destinationPort) {
        try {
            RtpSenderInstance sender = new RtpSenderInstance(conversationId, destinationHost, destinationPort);
            senders.put(conversationId, sender);
            logger.info("[{}] RTP Sender created for {}:{}", conversationId, destinationHost, destinationPort);
        } catch (Exception e) {
            logger.error("[{}] Failed to create RTP sender", conversationId, e);
        }
    }

    public void sendAudio(String conversationId, byte[] audioData) {
        RtpSenderInstance sender = senders.get(conversationId);
        if (sender != null) {

            logger.debug("[{}] {} byte ses verisi RTP üzerinden Asterisk'e gönderiliyor.", conversationId, audioData.length);

            sender.sendAudio(audioData);
        } else {
            logger.warn("[{}] No RTP sender found for conversation", conversationId);
        }
    }

    public void closeSender(String conversationId) {
        RtpSenderInstance sender = senders.remove(conversationId);
        if (sender != null) {
            sender.close();
            logger.info("[{}] RTP Sender closed", conversationId);
        }
    }

    private static class RtpSenderInstance {
        private final Logger logger = LoggerFactory.getLogger(RtpSenderInstance.class);
        private final String conversationId;
        private final DatagramSocket socket;
        private final InetAddress destinationAddress;
        private final int destinationPort;

        // RTP Header fields
        private final AtomicInteger sequenceNumber = new AtomicInteger(0);
        private final AtomicLong timestamp = new AtomicLong(0);
        private final int ssrc;

        // Timing
        private long lastSendTime = 0;
        private static final int SAMPLE_RATE = 8000; // Asterisk slin16
        private static final int BYTES_PER_SAMPLE = 2; // 16-bit
        private static final int TIMESTAMP_INCREMENT = 160; // 20ms at 8kHz

        public RtpSenderInstance(String conversationId, String host, int port) throws Exception {
            this.conversationId = conversationId;
            this.socket = new DatagramSocket();
            this.destinationAddress = InetAddress.getByName(host);
            this.destinationPort = port;
            this.ssrc = (int) (Math.random() * Integer.MAX_VALUE);

            socket.setSendBufferSize(64 * 1024); // 64KB send buffer
            socket.setTrafficClass(0x10); // Low delay
        }

        public void sendAudio(byte[] audioData) {
            if (audioData == null || audioData.length == 0) {
                return;
            }

            try {

                int chunkSize = 160; // 80 samples * 2 bytes = 160 bytes per 20ms

                for (int offset = 0; offset < audioData.length; offset += chunkSize) {
                    int remainingBytes = Math.min(chunkSize, audioData.length - offset);
                    byte[] chunk = new byte[remainingBytes];
                    System.arraycopy(audioData, offset, chunk, 0, remainingBytes);

                    sendRtpPacket(chunk);

                    // Pacing: 20ms intervals için timing
                    maintainPacing();
                }

            } catch (Exception e) {
                logger.error("[{}] Failed to send RTP audio packet", conversationId, e);
            }
        }

        private void sendRtpPacket(byte[] audioPayload) throws Exception {
            // RTP Header (12 bytes)
            byte[] rtpHeader = new byte[12];

            // Version (2), Padding (0), Extension (0), CSRC count (0)
            rtpHeader[0] = (byte) 0x80;

            // Marker (0), Payload Type (11 for slin16 or 0 for PCMU)
            rtpHeader[1] = (byte) 0x0B; // slin16 payload type

            // Sequence number (16 bits)
            int seqNum = sequenceNumber.getAndIncrement() & 0xFFFF;
            rtpHeader[2] = (byte) ((seqNum >> 8) & 0xFF);
            rtpHeader[3] = (byte) (seqNum & 0xFF);

            // Timestamp (32 bits)
            long ts = timestamp.getAndAdd(TIMESTAMP_INCREMENT) & 0xFFFFFFFFL;
            rtpHeader[4] = (byte) ((ts >> 24) & 0xFF);
            rtpHeader[5] = (byte) ((ts >> 16) & 0xFF);
            rtpHeader[6] = (byte) ((ts >> 8) & 0xFF);
            rtpHeader[7] = (byte) (ts & 0xFF);

            // SSRC (32 bits)
            rtpHeader[8] = (byte) ((ssrc >> 24) & 0xFF);
            rtpHeader[9] = (byte) ((ssrc >> 16) & 0xFF);
            rtpHeader[10] = (byte) ((ssrc >> 8) & 0xFF);
            rtpHeader[11] = (byte) (ssrc & 0xFF);

            // Combine header and payload
            byte[] rtpPacket = new byte[rtpHeader.length + audioPayload.length];
            System.arraycopy(rtpHeader, 0, rtpPacket, 0, rtpHeader.length);
            System.arraycopy(audioPayload, 0, rtpPacket, rtpHeader.length, audioPayload.length);

            // Send packet
            DatagramPacket packet = new DatagramPacket(
                    rtpPacket, rtpPacket.length,
                    destinationAddress, destinationPort
            );

            socket.send(packet);

            logger.trace("[{}] Sent RTP packet: seq={}, ts={}, size={}",
                    conversationId, seqNum, ts, rtpPacket.length);
        }

        private void maintainPacing() {
            // 20ms pacing between packets
            long currentTime = System.currentTimeMillis();
            if (lastSendTime > 0) {
                long elapsed = currentTime - lastSendTime;
                long targetInterval = 20; // 20ms

                if (elapsed < targetInterval) {
                    try {
                        Thread.sleep(targetInterval - elapsed);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            lastSendTime = System.currentTimeMillis();
        }

        public void close() {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }
}