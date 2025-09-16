package com.yusssss.vcmail.core.utilities.openai;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Base64;
import java.util.function.Consumer;

@Component
public class OpenAiRealtimeService {

    private final Logger logger = LoggerFactory.getLogger(OpenAiRealtimeService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();



    @Value("${openai.api.key}")
    private String apiKey;


    private WebSocketClient webSocketClient;


    public void startSession(Consumer<byte[]> onAudioReceived, Consumer<JsonNode> onToolCall, Consumer<String> onClose){

        String url = "wss://api.openai.com/v1/realtime?model=gpt-realtime";
        try{
            webSocketClient = new WebSocketClient(new URI(url)) {
                @Override
                public void onOpen(ServerHandshake serverHandshake) {
                    logger.info("SUCCESS: WebSocket connection opened");
                    sendSessionUpdate();

                }

                @Override
                public void onMessage(String message) {
                    try{
                        JsonNode event = objectMapper.readTree(message);
                        String type = event.path("type").asText();

                        logger.debug("Received OpenAI event: {}", type);

                        switch (type) {
                            case "response.output_audio.delta":
                                String audioBase64 = event.path("delta").asText();
                                if (!audioBase64.isEmpty()) {
                                    byte[] audioBytes = Base64.getDecoder().decode(audioBase64);
                                    onAudioReceived.accept(audioBytes);
                                }
                                break;

                            case "response.output_audio.done":
                                logger.debug("Audio output completed");
                                break;

                            case "tool.run.requested":
                                onToolCall.accept(event.path("data").path("tool_call"));
                                break;

                            case "response.done":
                                logger.debug("Response completed");
                                break;

                            case "conversation.item.input_audio_transcription.completed":
                                String transcript = event.path("transcript").asText();
                                logger.info("User transcript: {}", transcript);
                                break;

                            case "response.created":
                                logger.debug("Response creation started");
                                break;

                            case "session.created":
                                logger.info("OpenAI session created successfully");
                                break;

                            case "session.updated":
                                logger.info("OpenAI session updated successfully");
                                break;

                            case "input_audio_buffer.speech_started":
                                logger.debug("User started speaking");
                                break;

                            case "input_audio_buffer.speech_stopped":
                                logger.debug("User stopped speaking");
                                break;

                            case "error":
                                JsonNode error = event.path("error");
                                logger.error("OpenAI API Error: {} - {}",
                                        error.path("type").asText(),
                                        error.path("message").asText());
                                break;

                            default:
                                logger.trace("Unhandled OpenAI event type: {}", type);
                        }

                    } catch (Exception e) {
                        logger.error("Error parsing OpenAI event: {}", message, e);
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    logger.warn("OpenAI WebSocket closed. Code: {}, Reason: {}", code, reason);
                    onClose.accept(reason);
                }

                @Override
                public void onError(Exception e) {
                    logger.error("An error occurred in OpenAI WebSocket", e);
                }
            };

            webSocketClient.addHeader("Authorization", "Bearer " + apiKey);
            webSocketClient.connect();

         } catch (Exception e) {
            logger.error("Failed to initiate OpenAI WebSocket connection", e);
        }
    }



    private void sendSessionUpdate() {
        ObjectNode session = objectMapper.createObjectNode();
        session.put("type", "session.update");

        ObjectNode sessionConfig = objectMapper.createObjectNode();

        // Modality ayarları - ses ve text
        ArrayNode modalities = objectMapper.createArrayNode();
        modalities.add("text");
        modalities.add("audio");
        sessionConfig.set("modalities", modalities);

        sessionConfig.put("voice", "alloy");

        // Input audio format
        sessionConfig.put("input_audio_format", "pcm16");
        sessionConfig.put("output_audio_format", "pcm16");

        // Ses tanıma ayarları
        sessionConfig.put("turn_detection_type", "server_vad");

        ObjectNode vadConfig = objectMapper.createObjectNode();
        vadConfig.put("threshold", 0.5);
        vadConfig.put("prefix_padding_ms", 300);
        vadConfig.put("silence_duration_ms", 500);
        sessionConfig.set("turn_detection", vadConfig);

        // Sistem talimatları
        String systemMessage = "Sen bir sekretersin. Türkçe konuşuyorsun. Kısa ve öz cevaplar veriyorsun. " +
                "Arayan kişinin adını, telefon numarasını ve mesajını alıp kaydetmelisin. " +
                "Gerektiğinde randevu alabilirsin.";
        sessionConfig.put("instructions", systemMessage);

        // Sıcaklık ayarı
        sessionConfig.put("temperature", 0.7);

        // Tools tanımlaması
        ArrayNode tools = objectMapper.createArrayNode();

        // Mesaj kaydetme tool
        ObjectNode saveMessageTool = objectMapper.createObjectNode();
        saveMessageTool.put("type", "function");
        ObjectNode saveMessageFunction = objectMapper.createObjectNode();
        saveMessageFunction.put("name", "save_caller_message");
        saveMessageFunction.put("description", "Arayan kişinin mesajını ve bilgilerini kaydet");

        ObjectNode saveMessageParams = objectMapper.createObjectNode();
        saveMessageParams.put("type", "object");
        ObjectNode saveMessageProps = objectMapper.createObjectNode();

        ObjectNode callerName = objectMapper.createObjectNode();
        callerName.put("type", "string");
        callerName.put("description", "Arayan kişinin adı");
        saveMessageProps.set("caller_name", callerName);

        ObjectNode callerPhone = objectMapper.createObjectNode();
        callerPhone.put("type", "string");
        callerPhone.put("description", "Arayan kişinin telefon numarası");
        saveMessageProps.set("caller_phone", callerPhone);

        ObjectNode message = objectMapper.createObjectNode();
        message.put("type", "string");
        message.put("description", "Arayan kişinin bıraktığı mesaj");
        saveMessageProps.set("message", message);

        ObjectNode priority = objectMapper.createObjectNode();
        priority.put("type", "string");
        priority.put("enum", objectMapper.createArrayNode().add("düşük").add("orta").add("yüksek"));
        priority.put("description", "Mesajın öncelik seviyesi");
        saveMessageProps.set("priority", priority);

        saveMessageParams.set("properties", saveMessageProps);

        ArrayNode required = objectMapper.createArrayNode();
        required.add("caller_name");
        required.add("message");
        saveMessageParams.set("required", required);

        saveMessageFunction.set("parameters", saveMessageParams);
        saveMessageTool.set("function", saveMessageFunction);
        tools.add(saveMessageTool);


        ObjectNode appointmentTool = objectMapper.createObjectNode();
        appointmentTool.put("type", "function");
        ObjectNode appointmentFunction = objectMapper.createObjectNode();
        appointmentFunction.put("name", "schedule_appointment");
        appointmentFunction.put("description", "Arayan kişi için randevu al");

        ObjectNode appointmentParams = objectMapper.createObjectNode();
        appointmentParams.put("type", "object");
        ObjectNode appointmentProps = objectMapper.createObjectNode();

        ObjectNode appointmentDate = objectMapper.createObjectNode();
        appointmentDate.put("type", "string");
        appointmentDate.put("description", "Randevu tarihi (YYYY-MM-DD)");
        appointmentProps.set("date", appointmentDate);

        ObjectNode appointmentTime = objectMapper.createObjectNode();
        appointmentTime.put("type", "string");
        appointmentTime.put("description", "Randevu saati (HH:MM)");
        appointmentProps.set("time", appointmentTime);

        ObjectNode appointmentPurpose = objectMapper.createObjectNode();
        appointmentPurpose.put("type", "string");
        appointmentPurpose.put("description", "Randevu amacı");
        appointmentProps.set("purpose", appointmentPurpose);

        appointmentParams.set("properties", appointmentProps);
        ArrayNode appointmentRequired = objectMapper.createArrayNode();
        appointmentRequired.add("date");
        appointmentRequired.add("time");
        appointmentParams.set("required", appointmentRequired);

        appointmentFunction.set("parameters", appointmentParams);
        appointmentTool.set("function", appointmentFunction);
        tools.add(appointmentTool);

        sessionConfig.set("tools", tools);

        session.set("session", sessionConfig);
        sendJson(session);

        logger.info("OpenAI session configuration sent successfully");
    }

    public void sendAudio(byte[] audioData) {
        if (webSocketClient != null && webSocketClient.isOpen()) {
            ObjectNode audioEvent = objectMapper.createObjectNode();
            audioEvent.put("type", "input_audio_buffer.append");
            audioEvent.put("audio", Base64.getEncoder().encodeToString(audioData));
            sendJson(audioEvent);
        }
    }

    public void sendToolResult(String toolCallId, String result) {
        ObjectNode toolEvent = objectMapper.createObjectNode();
        toolEvent.put("type", "tool.run.completed");

        ObjectNode data = objectMapper.createObjectNode();
        data.put("tool_call_id", toolCallId);
        data.put("output", result);

        toolEvent.set("data", data);
        sendJson(toolEvent);
    }

    public void stopSession() {
        if (webSocketClient != null) {
            webSocketClient.close();
        }
    }

    public void sendTextPrompt(String text) {
        if (webSocketClient != null && webSocketClient.isOpen()) {
            ObjectNode event = objectMapper.createObjectNode();
            event.put("type", "conversation.item.create");

            ObjectNode item = objectMapper.createObjectNode();
            item.put("type", "message");
            item.put("role", "user");

            ObjectNode content = objectMapper.createObjectNode();
            content.put("type", "input_text");
            content.put("text", text);

            item.set("content", objectMapper.createArrayNode().add(content));
            event.set("item", item);

            sendJson(event);

            ObjectNode responseEvent = objectMapper.createObjectNode();
            responseEvent.put("type", "response.create");
            sendJson(responseEvent);
        }
    }

    private void sendJson(ObjectNode node) {
        try {
            String jsonString = objectMapper.writeValueAsString(node);
            webSocketClient.send(jsonString);
        } catch (Exception e) {
            logger.error("Failed to send JSON to OpenAI WebSocket", e);
        }
    }


    public void triggerInitialResponse() {
        if (webSocketClient != null && webSocketClient.isOpen()) {
            ObjectNode responseEvent = objectMapper.createObjectNode();
            responseEvent.put("type", "response.create");
            sendJson(responseEvent);
        }
    }
}
