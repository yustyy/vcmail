package com.yusssss.vcmail.core.utilities.ari;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.function.Consumer;

@Component
public class AriConnectionManager {

    private final Logger logger = LoggerFactory.getLogger(AriConnectionManager.class);

    private final RestTemplate restTemplate;

    private final ObjectMapper objectMapper;

    private WebSocketClient eventSocket;

    private Consumer<JsonNode> onStasisStart;

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



    @PostConstruct
    public void connect(){
        String wsUrl = String.format("ws://%s:%d/ari/events?api_key=%s:%s&app=%s",
                ariHost, ariPort, ariUser, ariPassword, ariApp);


        try{
            eventSocket = new WebSocketClient(new URI(wsUrl)) {
                @Override
                public void onOpen(ServerHandshake serverHandshake) {
                    logger.info("Connected to ARI WebSocket");
                }

                @Override
                public void onMessage(String message) {
                    try{
                        JsonNode event = objectMapper.readTree(message);
                        if ("StasisStart".equals(event.get("type").asText()) && onStasisStart != null) {
                                onStasisStart.accept(event);

                        }

                    }catch (Exception e){
                        logger.error("Error parsing ARI event: {}", e.getMessage());
                    }
                }

                @Override
                public void onClose(int i, String message, boolean b) {
                    logger.warn("ARI WebSocket connection closed Code: {}, Reason: {}", i, message);
                }

                @Override
                public void onError(Exception e) {
                    logger.error("An error occurred in ARI WebSocket.", e);
                }
            };

            eventSocket.connect();


        }catch (Exception e){
            logger.error("Failed to connect to ARI WebSocket: {}", e.getMessage());
        }


    }


    @PreDestroy
    public void disconnect(){
        if (eventSocket != null) {
            eventSocket.close();
            logger.info("Disconnected from ARI WebSocket");
        }
    }


    public void onStatisStart(Consumer<JsonNode> callback){
        this.onStasisStart = callback;
    }


    public void playAudio(String channelId, String soundFile) {
        String url = String.format("http://%s:%d/ari/channels/%s/play?api_key=%s:%s&media=sound:%s",
                ariHost, ariPort, channelId, ariUser, ariPassword, soundFile);

        try{
            HttpHeaders headers = new HttpHeaders();
            HttpEntity<String> entity = new HttpEntity<>(headers);


            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);



            if (!response.getStatusCode().is2xxSuccessful()){
                logger.error("Failed to execute ARI play command. Status: {}, Body: {}", response.getStatusCode(), response.getBody());
            }else {
                logger.info("ARI play command sent successfully for channel {}", channelId);
            }

        }catch (Exception e){
            logger.error("Error sending ARI play command for channel {}", channelId, e);
        }


    }

    public void bridgeRtp(String channelId, String destination){


        String url = String.format("http://%s:%d/ari/channels/%s/externalMedia?api_key=%s:%s&app=%s&external_host=%s&format=slin16",
                ariHost, ariPort, channelId, ariUser, ariPassword, ariApp, destination);


        try {
            HttpHeaders headers = new HttpHeaders();
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                logger.info("Successfully bridged channel {} to RTP destination {}", channelId, destination);
            } else {
                logger.error("Failed to bridge channel {}. Status: {}, Body: {}", channelId, response.getStatusCode(), response.getBody());
            }
        } catch (Exception e) {
            logger.error("Error sending ARI bridgeRtp command for channel {}", channelId, e);
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


}
