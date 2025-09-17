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

        String url = "wss://api.openai.com/v1/realtime?model=gpt-4o-mini-realtime-preview";
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
        sessionConfig.put("type", "realtime");
        sessionConfig.put("model", "gpt-4o-mini-realtime-preview");

        // Ses ayarları doğru, olduğu gibi kalabilir...
        ObjectNode audio = objectMapper.createObjectNode();
        ObjectNode audioInput = objectMapper.createObjectNode();
        ObjectNode inputFormat = objectMapper.createObjectNode();
        inputFormat.put("type", "audio/pcm");
        inputFormat.put("rate", 24000);
        audioInput.set("format", inputFormat);
        ObjectNode turnDetection = objectMapper.createObjectNode();
        turnDetection.put("type", "server_vad");
        turnDetection.put("threshold", 0.5);
        turnDetection.put("prefix_padding_ms", 300);
        turnDetection.put("silence_duration_ms", 500);
        audioInput.set("turn_detection", turnDetection);
        audio.set("input", audioInput);
        ObjectNode audioOutput = objectMapper.createObjectNode();
        audioOutput.put("voice", "alloy");
        ObjectNode outputFormat = objectMapper.createObjectNode();
        outputFormat.put("type", "audio/pcm");
        outputFormat.put("rate", 24000);
        audioOutput.set("format", outputFormat);
        audio.set("output", audioOutput);
        sessionConfig.set("audio", audio);

        String systemMessage = "Sen bir sekretersin. Türkçe konuşuyorsun. Kısa ve öz cevaplar veriyorsun. " +
                "İlk konuşmada 'Merhaba, klinik sekreterine hoş geldiniz. Ben Elara, size nasıl yardımcı olabilirim?' diye karşıla. " +
                "Arayan kişinin adını, telefon numarasını ve mesajını alıp kaydetmelisin. " +
                "Gerektiğinde randevu alabilirsin.";
        sessionConfig.put("instructions", systemMessage);

        sessionConfig.put("max_output_tokens", 4096);

        ArrayNode tools = objectMapper.createArrayNode();
        ObjectNode saveMessageTool = objectMapper.createObjectNode();
        saveMessageTool.put("type", "function");

        // Parametreler daha önce tanımlanıyor
        ObjectNode saveParams = objectMapper.createObjectNode();
        saveParams.put("type", "object");
        ObjectNode saveProperties = objectMapper.createObjectNode();
        ObjectNode callerName = objectMapper.createObjectNode();
        callerName.put("type", "string");
        callerName.put("description", "Arayan kişinin adı");
        saveProperties.set("caller_name", callerName);
        ObjectNode callerPhone = objectMapper.createObjectNode();
        callerPhone.put("type", "string");
        callerPhone.put("description", "Arayan kişinin telefon numarası");
        saveProperties.set("caller_phone", callerPhone);
        ObjectNode message = objectMapper.createObjectNode();
        message.put("type", "string");
        message.put("description", "Arayan kişinin bıraktığı mesaj");
        saveProperties.set("message", message);
        ObjectNode priority = objectMapper.createObjectNode();
        priority.put("type", "string");
        ArrayNode priorityEnum = objectMapper.createArrayNode();
        priorityEnum.add("düşük");
        priorityEnum.add("orta");
        priorityEnum.add("yüksek");
        priority.set("enum", priorityEnum);
        priority.put("description", "Mesajın öncelik seviyesi");
        saveProperties.set("priority", priority);
        saveParams.set("properties", saveProperties);
        ArrayNode saveRequired = objectMapper.createArrayNode();
        saveRequired.add("caller_name");
        saveRequired.add("message");
        saveParams.set("required", saveRequired);

        // Düzeltilmiş yapı: name, description ve parameters doğrudan tool nesnesine ekleniyor
        saveMessageTool.put("name", "save_caller_message");
        saveMessageTool.put("description", "Arayan kişinin mesajını ve bilgilerini kaydet");
        saveMessageTool.set("parameters", saveParams);

        tools.add(saveMessageTool);

        // --- Schedule appointment tool ---
        ObjectNode appointmentTool = objectMapper.createObjectNode();
        appointmentTool.put("type", "function");

        // Parametreler daha önce tanımlanıyor
        ObjectNode appointmentParams = objectMapper.createObjectNode();
        appointmentParams.put("type", "object");
        ObjectNode appointmentProperties = objectMapper.createObjectNode();
        ObjectNode patientName = objectMapper.createObjectNode();
        patientName.put("type", "string");
        patientName.put("description", "Hasta adı soyadı");
        appointmentProperties.set("caller_name", patientName);
        ObjectNode appointmentDate = objectMapper.createObjectNode();
        appointmentDate.put("type", "string");
        appointmentDate.put("description", "Randevu tarihi (YYYY-MM-DD formatında)");
        appointmentProperties.set("date", appointmentDate);
        ObjectNode appointmentTime = objectMapper.createObjectNode();
        appointmentTime.put("type", "string");
        appointmentTime.put("description", "Randevu saati (HH:MM formatında)");
        appointmentProperties.set("time", appointmentTime);
        ObjectNode appointmentPurpose = objectMapper.createObjectNode();
        appointmentPurpose.put("type", "string");
        appointmentPurpose.put("description", "Randevu amacı veya şikayeti");
        appointmentProperties.set("purpose", appointmentPurpose);
        appointmentParams.set("properties", appointmentProperties);
        ArrayNode appointmentRequired = objectMapper.createArrayNode();
        appointmentRequired.add("caller_name");
        appointmentRequired.add("date");
        appointmentRequired.add("time");
        appointmentParams.set("required", appointmentRequired);


        appointmentTool.put("name", "schedule_appointment");
        appointmentTool.put("description", "Arayan kişi için randevu ayarla");
        appointmentTool.set("parameters", appointmentParams);

        tools.add(appointmentTool);

        sessionConfig.set("tools", tools);
        session.set("session", sessionConfig);

        try {
            String jsonString = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(session);
            logger.info("Sending session config: {}", jsonString);
            sendJson(session);
        } catch (Exception e) {
            logger.error("Failed to serialize session config", e);
        }

        logger.info("OpenAI session configuration sent successfully");
    }

    public void sendAudio(byte[] audioData) {
        if (webSocketClient != null && webSocketClient.isOpen()) {

            logger.debug("OpenAI'a gönderilmek üzere {} byte Base64'e çevriliyor.", audioData.length);

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
