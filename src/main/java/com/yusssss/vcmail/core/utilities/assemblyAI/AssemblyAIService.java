package com.yusssss.vcmail.core.utilities.assemblyAI;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.function.Consumer;


@Component
public class AssemblyAIService {

    private final Logger logger = LoggerFactory.getLogger(AssemblyAIService.class);

    @Value("${assemblyai.api.key}")
    private String assemblyAiApiKey;

    private WebSocketClient webSocketClient;

    private final ObjectMapper objectMapper = new ObjectMapper();



    public void startSession(Consumer<String> onTranscriptReceived, Consumer<Exception> onError, Consumer<String> onClose) {
        logger.info("Starting session...");
        try {
            String url = "wss://streaming.assemblyai.com/v3/ws?sample_rate=8000";
            URI serverUri = new URI(url);

            this.webSocketClient = new WebSocketClient(serverUri) {
                @Override
                public void onOpen(ServerHandshake serverHandshake) {
                    logger.info("AssemblyAI WebSocket connection opened.");
                }

                @Override
                public void onMessage(String messageJson) {
                    logger.info("AssemblyAI WebSocket message received.");
                    try{
                       JsonNode rootNode = objectMapper.readTree(messageJson);
                       String messageType = rootNode.get("type").asText();

                       if ("Turn".equals(messageType)) {
                           boolean isEndOfTurn = rootNode.get("end_of_turn").asBoolean();
                           String transcript = rootNode.get("transcript").asText();

                           if (isEndOfTurn && !transcript.isEmpty()){
                               logger.info("Final transcript received: {}", transcript);
                                 onTranscriptReceived.accept(transcript);
                           }else {
                               logger.info("Partial transcript received: {}", transcript);
                           }
                       }else {
                           logger.info("Received non-Turn message of type: {}", messageJson);
                       }

                    }catch (Exception e){
                        logger.error("Error parsing AssemblyAI WebSocket message: {}", messageJson, e);
                        onError.accept(e);

                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    logger.info("WebSocket connection closed. Code: {}, Reason: {}", code, reason);
                    onClose.accept("Connection closed with code " + code + ": " + reason);
                }

                @Override
                public void onError(Exception e) {
                    logger.error("AssemblyAI WebSocket connection error.", e);
                    onError.accept(e);
                }
            };


            this.webSocketClient.addHeader("Authorization", assemblyAiApiKey);
            logger.info("Connecting to AssemblyAI...");
            this.webSocketClient.connect();


        }catch (Exception e){
            logger.error("AssemblyAI WebSocket connection error.", e);
            onError.accept(e);
        }

    }

    public void sendAudio(byte[] audioData){
        if (webSocketClient != null && webSocketClient.isOpen()){
            webSocketClient.send(audioData);
        }
    }


    public void stopSession(){
        if (webSocketClient != null){
            webSocketClient.close();
            logger.info("AssemblyAI WebSocket connection closed.");
        }

    }


}
