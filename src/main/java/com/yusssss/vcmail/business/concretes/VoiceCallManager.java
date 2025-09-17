package com.yusssss.vcmail.business.concretes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yusssss.vcmail.business.abstracts.ConversationService;
import com.yusssss.vcmail.core.utilities.ari.AriConnectionManager;
import com.yusssss.vcmail.core.utilities.audio.AudioConversionService;
import com.yusssss.vcmail.core.utilities.openai.OpenAiRealtimeService;
import com.yusssss.vcmail.core.utilities.rtp.RtpAudioSender;
import com.yusssss.vcmail.core.utilities.rtp.RtpListener;
import com.yusssss.vcmail.core.utilities.rtp.RtpListenerFactory;
import com.yusssss.vcmail.entities.Conversation;
import com.yusssss.vcmail.entities.Message;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class VoiceCallManager {

    private final ConversationService conversationService;
    private final AriConnectionManager ariConnectionManager;
    private final RtpListenerFactory rtpListenerFactory;
    private final OpenAiRealtimeService openAiRealtimeService;
    private final AudioConversionService audioConversionService;
    private final RtpAudioSender rtpAudioSender;
    private final Logger logger = LoggerFactory.getLogger(VoiceCallManager.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Call state tracking
    private final Map<String, String> channelIdToConversationIdMap = new ConcurrentHashMap<>();
    private final Map<String, String> conversationIdToMediaChannelIdMap = new ConcurrentHashMap<>();
    private final Map<String, String> conversationIdToCallerNumberMap = new ConcurrentHashMap<>();



    private final Map<String, ByteArrayOutputStream> conversationAudioBuffers = new ConcurrentHashMap<>();

    private final Map<String, ByteArrayOutputStream> conversationOpenAiAudioBuffers = new ConcurrentHashMap<>();

    private final Map<String, ByteArrayOutputStream> conversationAssistantAudioBuffers = new ConcurrentHashMap<>();

    @Value("${asterisk.ari.rtp-host}")
    private String rtpHost;


    @Value("${asterisk.ari.rtp-app-host}")
    private String rtpAppHost;

    public VoiceCallManager(ConversationService conversationService,
                            OpenAiRealtimeService openAiRealtimeService,
                            AriConnectionManager ariConnectionManager,
                            RtpListenerFactory rtpListenerFactory,
                            AudioConversionService audioConversionService,
                            RtpAudioSender rtpAudioSender) {
        this.conversationService = conversationService;
        this.openAiRealtimeService = openAiRealtimeService;
        this.ariConnectionManager = ariConnectionManager;
        this.rtpListenerFactory = rtpListenerFactory;
        this.audioConversionService = audioConversionService;
        this.rtpAudioSender = rtpAudioSender;
    }

    @PostConstruct
    public void initialize() {
        logger.info("Initializing VoiceCallManager and setting ARI event listeners.");
        ariConnectionManager.onStasisStart(this::handleStasisStartEvent);
        ariConnectionManager.onStasisEnd(this::handleStasisEndEvent);
    }

    private void handleStasisStartEvent(JsonNode stasisStartEvent) {
        String channelId = stasisStartEvent.path("channel").path("id").asText();
        JsonNode callerNode = stasisStartEvent.path("channel").path("caller");

        // External media channel'ları ignore et
        if (callerNode.isMissingNode() || !callerNode.has("number") ||
                callerNode.path("number").asText().isEmpty() ||
                channelIdToConversationIdMap.containsKey(channelId)) {
            logger.warn("Ignoring StasisStart event for internal or duplicate channel: {}", channelId);
            return;
        }

        String callerNumber = callerNode.path("number").asText();
        logger.info("🔄 NEW INCOMING CALL - Channel: {}, Caller: {}", channelId, callerNumber);

        // Conversation oluştur
        Conversation conversation = conversationService.startConversation();
        String conversationId = conversation.getId();

        conversationAudioBuffers.put(conversationId, new ByteArrayOutputStream());
        conversationOpenAiAudioBuffers.put(conversationId, new ByteArrayOutputStream());
        conversationAssistantAudioBuffers.put(conversationId, new ByteArrayOutputStream());


        channelIdToConversationIdMap.put(channelId, conversationId);
        conversationIdToCallerNumberMap.put(conversationId, callerNumber);

        // Bridge oluştur
        String bridgeId = ariConnectionManager.createBridge();
        if (bridgeId == null) {
            logger.error("[{}] ❌ Could not create bridge. Ending call.", conversationId);
            endCall(conversationId, channelId, "BRIDGE_CREATION_FAILED", true);
            return;
        }

        // Caller channel'ı bridge'e ekle
        ariConnectionManager.addChannelToBridge(bridgeId, channelId);

        // RTP Listener oluştur
        RtpListener rtpListener = rtpListenerFactory.createListener(conversationId);
        rtpListener.start();
        int listeningPort = rtpListener.getPort();

        // External media channel oluştur
        JsonNode externalMediaChannel = ariConnectionManager.createExternalMediaChannel(rtpAppHost + ":" + listeningPort);
        if (externalMediaChannel == null) {
            logger.error("[{}] ❌ Could not create external media channel. Ending call.", conversationId);
            endCall(conversationId, channelId, "MEDIA_CHANNEL_FAILED", true);
            return;
        }


        int asteriskMediaPort = externalMediaChannel.path("local_port").asInt();
        if (asteriskMediaPort == 0) {
            logger.error("[{}] ❌ Could not get Asterisk's media port. Ending call.", conversationId);
            endCall(conversationId, channelId, "MEDIA_PORT_FAILED", true);
            return;
        }
        logger.info("[{}] Asterisk is listening for our audio on port {}", conversationId, asteriskMediaPort);


        String mediaChannelId = externalMediaChannel.path("id").asText();
        conversationIdToMediaChannelIdMap.put(conversationId, mediaChannelId);
        ariConnectionManager.addChannelToBridge(bridgeId, mediaChannelId);

        // RTP Audio Sender oluştur
        rtpAudioSender.createSender(conversationId, rtpHost, asteriskMediaPort);

        // Audio processing pipeline kurulum
        setupAudioPipeline(conversationId, rtpListener);

        // OpenAI session başlat
        setupOpenAiSession(conversationId, channelId);

        logger.info("[{}] ✅ Call setup completed successfully", conversationId);
    }

    private void setupAudioPipeline(String conversationId, RtpListener rtpListener) {
        rtpListener.onAudioData(audioData -> {
            try {

                ByteArrayOutputStream audioBuffer = conversationAudioBuffers.get(conversationId);
                if (audioBuffer != null) {
                    try {
                        audioBuffer.write(audioData);
                    } catch (Exception e) {
                        logger.error("[{}] Sesi tampona yazarken hata oluştu", conversationId, e);
                    }
                }

                byte[] convertedAudio = audioConversionService.convertAsteriskToOpenAi(audioData);


                if (convertedAudio != null && convertedAudio.length > 0) {
                    conversationOpenAiAudioBuffers.get(conversationId).write(convertedAudio);
                }

                if (convertedAudio != null && convertedAudio.length > 0) {
                    byte[] normalizedAudio = audioConversionService.normalizeVolume(convertedAudio, 0.7f);
                    openAiRealtimeService.sendAudio(normalizedAudio);

                    logger.debug("[{}] 🎤 Audio sent to OpenAI: {} bytes", conversationId, normalizedAudio.length);
                }
            } catch (Exception e) {
                logger.error("[{}] ❌ Error processing incoming audio", conversationId, e);
            }
        });
    }

    private void setupOpenAiSession(String conversationId, String channelId) {
        String callerNumber = conversationIdToCallerNumberMap.get(conversationId);
        String mediaChannelId = conversationIdToMediaChannelIdMap.get(conversationId);

        openAiRealtimeService.startSession(
                // OpenAI'den gelen ses -> Asterisk'e gönder
                audioBytes -> {

                    logger.debug("OpenAI'dan {} byte ses verisi alındı. Asterisk'e gönderilecek.", audioBytes.length);

                    try {

                        ByteArrayOutputStream assistantAudioBuffer = conversationAssistantAudioBuffers.get(conversationId);
                        if (assistantAudioBuffer != null) {
                            assistantAudioBuffer.write(audioBytes);
                        }


                        // OpenAI audio'yu Asterisk formatına dönüştür
                        byte[] convertedAudio = audioConversionService.convertOpenAiToAsterisk(audioBytes);

                        if (convertedAudio.length > 0) {
                            // RTP ile gönder
                            rtpAudioSender.sendAudio(conversationId, convertedAudio);
                            logger.trace("[{}] 🔊 Audio sent to Asterisk: {} bytes", conversationId, convertedAudio.length);
                        }
                    } catch (Exception e) {
                        logger.error("[{}] ❌ Error processing outgoing audio", conversationId, e);
                    }
                },

                // Tool call handler
                toolCall -> processToolCall(conversationId, toolCall, callerNumber),

                // Session close handler
                reason -> {
                    logger.warn("[{}] 🔌 OpenAI session closed: {}", conversationId, reason);
                    endCall(conversationId, channelId, "OPENAI_CLOSED", false);
                }
        );

        // Initial response'u tetikle (welcome message için)
        logger.info("[{}] 🤖 Triggering initial AI response...", conversationId);
        openAiRealtimeService.triggerInitialResponse();
    }

    private void processToolCall(String conversationId, JsonNode toolCall, String callerNumber) {
        String toolName = toolCall.path("function").path("name").asText();
        String toolCallId = toolCall.path("tool_call_id").asText();
        JsonNode arguments = toolCall.path("function").path("arguments");

        logger.info("[{}] 🛠️  TOOL CALLED: {} with args: {}", conversationId, toolName, arguments);

        String result;
        boolean success = false;

        try {
            switch (toolName) {
                case "save_caller_message":
                    result = handleSaveCallerMessage(conversationId, arguments, callerNumber);
                    success = true;
                    break;

                case "schedule_appointment":
                    result = handleScheduleAppointment(conversationId, arguments, callerNumber);
                    success = true;
                    break;

                default:
                    result = "{\"error\":\"Unknown tool: " + toolName + "\"}";
                    logger.warn("[{}] ⚠️ Unknown tool requested: {}", conversationId, toolName);
            }
        } catch (Exception e) {
            logger.error("[{}] ❌ Error executing tool {}: {}", conversationId, toolName, e.getMessage());
            result = "{\"error\":\"Tool execution failed: " + e.getMessage() + "\"}";
        }

        // Tool sonucunu OpenAI'ye gönder
        openAiRealtimeService.sendToolResult(toolCallId, result);

        // Tool call'u conversation'a kaydet
        saveToolCallMessage(conversationId, toolName, arguments.toString(), result, success);
    }

    private String handleSaveCallerMessage(String conversationId, JsonNode arguments, String callerNumber) {
        try {
            String callerName = arguments.path("caller_name").asText("");
            String callerPhone = arguments.path("caller_phone").asText(callerNumber); // Default caller number
            String message = arguments.path("message").asText("");
            String priority = arguments.path("priority").asText("orta");

            // Terminal'e yazdır
            System.out.println("\n" + "=".repeat(60));
            System.out.println("📞 YENİ MESAJ KAYDI");
            System.out.println("=".repeat(60));
            System.out.println("🕒 Tarih/Saat: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")));
            System.out.println("👤 Arayan: " + callerName);
            System.out.println("📱 Telefon: " + callerPhone);
            System.out.println("⚡ Öncelik: " + priority.toUpperCase());
            System.out.println("💬 Mesaj:");
            System.out.println("   " + message);
            System.out.println("🔗 Conversation ID: " + conversationId);
            System.out.println("=".repeat(60) + "\n");

            // Veritabanına kaydetme işlemi burada olacak
            logger.info("[{}] Message saved to console - Name: {}, Phone: {}, Priority: {}",
                    conversationId, callerName, callerPhone, priority);

            ObjectNode response = objectMapper.createObjectNode();
            response.put("status", "success");
            response.put("message", "Mesajınız başarıyla kaydedildi");
            response.put("reference_id", conversationId);

            return response.toString();

        } catch (Exception e) {
            logger.error("[{}] Error saving caller message: {}", conversationId, e.getMessage());
            return "{\"status\":\"error\",\"message\":\"Mesaj kaydedilirken hata oluştu\"}";
        }
    }

    private String handleScheduleAppointment(String conversationId, JsonNode arguments, String callerNumber) {
        try {
            String date = arguments.path("date").asText("");
            String time = arguments.path("time").asText("");
            String purpose = arguments.path("purpose").asText("");
            String callerName = arguments.path("caller_name").asText("Bilinmeyen");

            // Terminal'e randevu bilgilerini yazdır
            System.out.println("\n" + "=".repeat(60));
            System.out.println("📅 YENİ RANDEVU KAYDI");
            System.out.println("=".repeat(60));
            System.out.println("🕒 Kayıt Zamanı: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")));
            System.out.println("👤 Hasta: " + callerName);
            System.out.println("📱 Telefon: " + callerNumber);
            System.out.println("📅 Randevu Tarihi: " + date);
            System.out.println("⏰ Randevu Saati: " + time);
            System.out.println("🎯 Amaç: " + purpose);
            System.out.println("🔗 Conversation ID: " + conversationId);
            System.out.println("📋 Durum: ONAY BEKLİYOR");
            System.out.println("=".repeat(60));
            System.out.println("⚠️  NOT: Bu randevu sisteme kaydedildi ve manuel onay bekliyor.");
            System.out.println("=".repeat(60) + "\n");

            // Basit availability check (şimdilik her zaman müsait)
            boolean isAvailable = checkAppointmentAvailability(date, time);

            if (!isAvailable) {
                return "{\"status\":\"error\",\"message\":\"Bu tarih ve saat müsait değil. Alternatif bir zaman önerebilirim.\"}";
            }

            // Randevu ID oluştur
            String appointmentId = "APT-" + System.currentTimeMillis();

            ObjectNode response = objectMapper.createObjectNode();
            response.put("status", "success");
            response.put("message", "Randevunuz " + date + " " + time + " için ayarlandı");
            response.put("appointment_id", appointmentId);

            return response.toString();

        } catch (Exception e) {
            logger.error("[{}] Error scheduling appointment: {}", conversationId, e.getMessage());
            return "{\"status\":\"error\",\"message\":\"Randevu ayarlanırken hata oluştu\"}";
        }
    }

    private boolean checkAppointmentAvailability(String date, String time) {
        // TODO: Gerçek randevu sistemiyle entegrasyon
        logger.info("Checking appointment availability for {} {}", date, time);
        return true; // Şimdilik her zaman müsait
    }

    private void handleStasisEndEvent(JsonNode stasisEndEvent) {
        String channelId = stasisEndEvent.path("channel").path("id").asText();
        String conversationId = channelIdToConversationIdMap.get(channelId);

        if (conversationId != null) {
            logger.info("[{}] 📞 Call ended - StasisEnd event received for channel {}", conversationId, channelId);
            endCall(conversationId, channelId, "CALL_ENDED", false);
        }
    }

    private void endCall(String conversationId, String channelId, String status, boolean forceHangup) {
        if (channelIdToConversationIdMap.remove(channelId) != null) {
            logger.info("[{}] 🧹 Cleaning up call resources - Status: {}", conversationId, status);


            saveConversationAudioToFile(conversationId, "user_audio", conversationAudioBuffers, "ulaw");

            saveConversationAudioToFile(conversationId, "openai_bound_audio", conversationOpenAiAudioBuffers, "pcm");

            saveConversationAudioToFile(conversationId, "assistant_audio", conversationAssistantAudioBuffers, "pcm");

            // RTP resources temizle
            rtpListenerFactory.stopListener(conversationId);
            rtpAudioSender.closeSender(conversationId);

            // OpenAI session kapat
            openAiRealtimeService.stopSession();

            // Maps'leri temizle
            conversationIdToMediaChannelIdMap.remove(conversationId);
            conversationIdToCallerNumberMap.remove(conversationId);

            // Conversation'ı sonlandır (şimdilik comment)
            // conversationService.endConversation(conversationId, status);

            // Force hangup gerekirse
            if (forceHangup) {
                ariConnectionManager.hangupChannel(channelId);
                logger.info("[{}] 📞 Force hangup executed", conversationId);
            }

            // Final log
            System.out.println("\n" + "=".repeat(60));
            System.out.println("📞 ARAMA SONLANDI");
            System.out.println("=".repeat(60));
            System.out.println("🕒 Bitiş Zamanı: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")));
            System.out.println("🔗 Conversation ID: " + conversationId);
            System.out.println("📱 Channel ID: " + channelId);
            System.out.println("📊 Durum: " + status);
            System.out.println("=".repeat(60) + "\n");
        }
    }

    private void saveConversationAudioToFile(String conversationId, String prefix, Map<String, ByteArrayOutputStream> bufferMap, String format) {
        ByteArrayOutputStream audioBuffer = bufferMap.remove(conversationId);
        if (audioBuffer == null || audioBuffer.size() == 0) {
            logger.warn("[{}] Kaydedilecek {} verisi bulunamadı.", conversationId, prefix);
            return;
        }

        try {
            byte[] audioBytes = audioBuffer.toByteArray();

            // Hangi tamponu kaydediyorsak ona göre formatı belirleyelim
            AudioFormat audioFormat;
            if ("ulaw".equals(format)) {
                audioFormat = new AudioFormat(AudioFormat.Encoding.ULAW, 8000.0f, 8, 1, 1, 8000.0f, false);
            } else {
                audioFormat = new AudioFormat(24000.0f, 16, 1, true, false);
            }

            ByteArrayInputStream bais = new ByteArrayInputStream(audioBytes);
            AudioInputStream audioInputStream = new AudioInputStream(bais, audioFormat, audioBytes.length / audioFormat.getFrameSize());

            String fileName = prefix + "_" + conversationId + ".wav";
            java.io.File outputFile = new java.io.File("kayitlar/" + fileName); // Kayıtları 'kayitlar' klasörüne yazıyoruz

            AudioSystem.write(audioInputStream, javax.sound.sampled.AudioFileFormat.Type.WAVE, outputFile);
            logger.info("📞 Ses kaydı kaydedildi: {}", outputFile.getAbsolutePath());
            audioInputStream.close();

        } catch (Exception e) {
            logger.error("[{}] {} kaydı dosyaya yazılırken hata oluştu.", conversationId, prefix, e);
        }
    }

    private void saveToolCallMessage(String conversationId, String toolName, String arguments, String result, boolean success) {
        Message message = new Message();
        message.setSpeaker("SYSTEM");
        message.setText(String.format("Tool Call: %s | Args: %s | Result: %s | Success: %s",
                toolName, arguments, result, success));
        conversationService.addMessage(conversationId, message);
    }

    private void saveUserMessage(String conversationId, String text) {
        Message message = new Message();
        message.setSpeaker("USER");
        message.setText(text);
        conversationService.addMessage(conversationId, message);
    }

    private void saveAssistantMessage(String conversationId, String text) {
        Message message = new Message();
        message.setSpeaker("ASSISTANT");
        message.setText(text);
        conversationService.addMessage(conversationId, message);
    }

    // Utility methods for debugging and monitoring
    public int getActiveCallCount() {
        return channelIdToConversationIdMap.size();
    }

    public void logActiveCallsStatus() {
        logger.info("Active calls: {}", getActiveCallCount());
        channelIdToConversationIdMap.forEach((channelId, conversationId) -> {
            String callerNumber = conversationIdToCallerNumberMap.get(conversationId);
            logger.info("  - Channel: {} | Conversation: {} | Caller: {}",
                    channelId, conversationId, callerNumber);
        });
    }
}