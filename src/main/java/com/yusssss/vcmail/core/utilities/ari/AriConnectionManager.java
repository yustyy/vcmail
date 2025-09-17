package com.yusssss.vcmail.core.utilities.ari;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Map;
import java.util.function.Consumer;

@Component
public class AriConnectionManager {

    private final Logger logger = LoggerFactory.getLogger(AriConnectionManager.class);
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private WebSocketClient eventSocket;
    private Consumer<JsonNode> onStasisStart;
    private Consumer<JsonNode> onStasisEnd;

    @Value("${asterisk.ari.host:localhost}")
    private String ariHost;
    @Value("${asterisk.ari.port:8088}")
    private int ariPort;
    @Value("${asterisk.ari.user:java-app}")
    private String ariUser;
    @Value("${asterisk.ari.password:cokguclusifre}")
    private String ariPassword;
    @Value("${asterisk.ari.app:vcmail-app}")
    private String ariApp;

    public AriConnectionManager(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public void connect() {
        if (isConnected()) {
            logger.info("ARI WebSocket is already connected.");
            return;
        }

        String wsUrl = String.format("ws://%s:%d/ari/events?api_key=%s:%s&app=%s",
                ariHost, ariPort, ariUser, ariPassword, ariApp);

        try {
            logger.info("Attempting to connect to ARI WebSocket at {}", wsUrl);
            eventSocket = new WebSocketClient(new URI(wsUrl)) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    logger.info("SUCCESS: Connected to ARI WebSocket.");
                }

                @Override
                public void onMessage(String message) {
                    try {
                        JsonNode event = objectMapper.readTree(message);
                        String eventType = event.path("type").asText();

                        if ("StasisStart".equals(eventType) && onStasisStart != null) {
                            onStasisStart.accept(event);
                        }
                        else if ("StasisEnd".equals(eventType) && onStasisEnd != null) {
                            onStasisEnd.accept(event);
                        }
                    } catch (Exception e) {
                        logger.error("Error parsing ARI event", e);
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    logger.warn("ARI WebSocket connection closed. Code: {}, Reason: {}", code, reason);
                }

                @Override
                public void onError(Exception ex) {
                    logger.error("An error occurred in ARI WebSocket: {}", ex.getMessage());
                }
            };
            eventSocket.connect();
        } catch (Exception e) {
            logger.error("Failed to initiate ARI WebSocket connection: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void disconnect() {
        try {
            if (eventSocket != null && eventSocket.isOpen()) {
                eventSocket.closeBlocking();
            }
        } catch (InterruptedException e) {
            logger.warn("Interrupted while closing WebSocket", e);
            Thread.currentThread().interrupt();
        }
    }

    public boolean isConnected() {
        return eventSocket != null && eventSocket.isOpen();
    }

    public void onStasisStart(Consumer<JsonNode> callback) {
        this.onStasisStart = callback;
    }

    public void onStasisEnd(Consumer<JsonNode> callback) {
        this.onStasisEnd = callback;
    }

    public void playAudio(String channelId, String soundFile) {
        String url = String.format("http://%s:%d/ari/channels/%s/play?api_key=%s:%s&media=sound:%s",
                ariHost, ariPort, channelId, ariUser, ariPassword, soundFile);
        try {
            restTemplate.postForEntity(url, null, String.class);
            logger.info("ARI 'playAudio' command sent successfully for channel {}", channelId);
        } catch (Exception e) {
            logger.error("Error sending ARI 'playAudio' command for channel {}", channelId, e);
        }
    }

    public JsonNode createExternalMediaChannel(String rtpDestination) {
        String url = String.format("http://%s:%d/ari/channels/externalMedia?api_key=%s:%s",
                ariHost, ariPort, ariUser, ariPassword);

        Map<String, String> body = Map.of(
                "app", ariApp,
                "external_host", rtpDestination,
                "format", "ulaw"
        );

        try {
            String jsonBody = objectMapper.writeValueAsString(body);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(jsonBody, headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                JsonNode channelNode = objectMapper.readTree(response.getBody());
                String channelId = channelNode.path("id").asText();

                logger.info("Successfully created external media channel {} for {}", channelId, rtpDestination);

                logger.debug("External media channel {} will trigger StasisStart - this should be ignored by VoiceCallManager", channelId);

                return channelNode;
            } else {
                logger.error("Failed to create external media channel. Status: {}, Body: {}", response.getStatusCode(), response.getBody());
            }
        } catch (Exception e) {
            logger.error("Error sending ARI createExternalMediaChannel command", e);
        }
        return null;
    }


    public String createBridge() {
        String url = String.format("http://%s:%d/ari/bridges?api_key=%s:%s",
                ariHost, ariPort, ariUser, ariPassword);

        try {
            String jsonBody = "{\"type\":\"mixing\"}";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(jsonBody, headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                JsonNode bridgeNode = objectMapper.readTree(response.getBody());
                String bridgeId = bridgeNode.path("id").asText();
                logger.info("Successfully created bridge with ID: {}", bridgeId);
                return bridgeId;
            } else {
                logger.error("Failed to create bridge. Status: {}, Body: {}", response.getStatusCode(), response.getBody());
            }
        } catch (Exception e) {
            logger.error("Error sending ARI createBridge command", e);
        }
        return null;
    }

    public void addChannelToBridge(String bridgeId, String channelId) {
        String url = String.format("http://%s:%d/ari/bridges/%s/addChannel?api_key=%s:%s",
                ariHost, ariPort, bridgeId, ariUser, ariPassword);

        String jsonBody = "{\"channel\":\"" + channelId + "\"}";

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(jsonBody, headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                logger.info("Successfully added channel {} to bridge {}", channelId, bridgeId);
            } else {
                logger.error("Failed to add channel {} to bridge {}. Status: {}, Body: {}",
                        channelId, bridgeId, response.getStatusCode(), response.getBody());
            }
        } catch (Exception e) {
            logger.error("Error sending ARI addChannelToBridge command", e);
        }
    }


    public void hangupChannel(String channelId) {
        String url = String.format("http://%s:%d/ari/channels/%s?api_key=%s:%s",
                ariHost, ariPort, channelId, ariUser, ariPassword);
        try {
            restTemplate.delete(url);
            logger.info("ARI hangup command sent successfully for channel {}", channelId);
        } catch (Exception e) {
            logger.error("Error sending ARI hangup command for channel {}", channelId, e);
        }
    }

    private void sendPostRequest(String url, String commandName, String channelId) {
        try {
            HttpHeaders headers = new HttpHeaders();
            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                logger.info("ARI '{}' command sent successfully for channel {}", commandName, channelId);
            } else {
                logger.error("Failed to execute ARI '{}' command for channel {}. Status: {}, Body: {}",
                        commandName, channelId, response.getStatusCode(), response.getBody());
            }
        } catch (Exception e) {
            logger.error("Error sending ARI '{}' command for channel {}", commandName, e);
        }
    }



    private void sendRtpAudio(String channelId, byte[] audioData) {
        String conversationId = getConversationIdByChannelId(channelId);


        logger.debug("[{}] Sent {} bytes of audio to channel {}", conversationId, audioData.length, channelId);
    }

    private String getConversationIdByChannelId(String channelId) {
        return "unknown-conversation";
    }

    private byte[] convertAudioFormat(byte[] pcm16Data) {

        byte[] downsampled = new byte[pcm16Data.length / 6];
        for (int i = 0, j = 0; i < pcm16Data.length - 5; i += 6, j += 2) {
            downsampled[j] = pcm16Data[i];
            downsampled[j + 1] = pcm16Data[i + 1];
        }

        byte[] ulawData = new byte[downsampled.length / 2];
        for (int i = 0, j = 0; i < downsampled.length - 1; i += 2, j++) {

            short pcmSample = (short) ((downsampled[i + 1] << 8) | (downsampled[i] & 0xFF));

            ulawData[j] = linearToUlaw(pcmSample);
        }

        return ulawData;
    }

    private byte linearToUlaw(short pcm) {

        int sign = (pcm >> 8) & 0x80;
        if (sign != 0) pcm = (short) -pcm;
        if (pcm > 32635) pcm = 32635;

        int exp = 7;
        int expMask = 0x4000;
        while ((pcm & expMask) == 0 && exp > 0) {
            exp--;
            expMask >>= 1;
        }

        int mantissa = (pcm >> (exp + 3)) & 0x0F;
        int ulaw = ~(sign | (exp << 4) | mantissa);

        return (byte) ulaw;
    }
}