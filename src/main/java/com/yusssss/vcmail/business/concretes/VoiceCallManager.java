package com.yusssss.vcmail.business.concretes;

import com.fasterxml.jackson.databind.JsonNode;
import com.yusssss.vcmail.business.abstracts.ConversationService;
import com.yusssss.vcmail.core.utilities.ari.AriConnectionManager;
import com.yusssss.vcmail.core.utilities.assemblyAI.AssemblyAIService;
import com.yusssss.vcmail.core.utilities.rtp.RtpListener;
import com.yusssss.vcmail.core.utilities.rtp.RtpListenerFactory;
import com.yusssss.vcmail.entities.Conversation;
import com.yusssss.vcmail.entities.Message;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class VoiceCallManager {

    private final ConversationService conversationService;
    private final AriConnectionManager ariConnectionManager;
    private final RtpListenerFactory rtpListenerFactory;
    private final AssemblyAIService assemblyAIService;
    private final Logger logger = LoggerFactory.getLogger(VoiceCallManager.class);


    private final Map<String, String> channelIdToConversationIdMap = new ConcurrentHashMap<>();

    @Value("${asterisk.ari.rtp-host}")
    private String rtpHost;

    public VoiceCallManager(ConversationService conversationService,
                            AssemblyAIService assemblyAIService,
                            AriConnectionManager ariConnectionManager,
                            RtpListenerFactory rtpListenerFactory) {
        this.conversationService = conversationService;
        this.assemblyAIService = assemblyAIService;
        this.ariConnectionManager = ariConnectionManager;
        this.rtpListenerFactory = rtpListenerFactory;
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

        if (callerNode.isMissingNode() || !callerNode.has("number") || callerNode.path("number").asText().isEmpty() || channelIdToConversationIdMap.containsKey(channelId)) {
            logger.warn("Ignoring StasisStart event for internal or duplicate channel: {}", channelId);
            return;
        }

        String callerNumber = callerNode.path("number").asText();
        logger.info("Handling a new call. Channel: {}, Caller: {}", channelId, callerNumber);

        Conversation conversation = conversationService.startConversation();
        String conversationId = conversation.getId();
        channelIdToConversationIdMap.put(channelId, conversationId);

        String bridgeId = ariConnectionManager.createBridge();
        if (bridgeId == null) {
            logger.error("[{}] Could not create a bridge. Ending call.", conversationId);
            endCall(conversationId, channelId, "ERROR", true);
            return;
        }
        ariConnectionManager.addChannelToBridge(bridgeId, channelId);

        RtpListener rtpListener = rtpListenerFactory.createListener(conversationId);
        rtpListener.start();
        int listeningPort = rtpListener.getPort();

        JsonNode externalMediaChannel = ariConnectionManager.createExternalMediaChannel(rtpHost + ":" + listeningPort);
        if (externalMediaChannel == null) {
            logger.error("[{}] Could not create external media channel. Ending call.", conversationId);
            endCall(conversationId, channelId, "ERROR", true);
            return;
        }
        String mediaChannelId = externalMediaChannel.path("id").asText();
        ariConnectionManager.addChannelToBridge(bridgeId, mediaChannelId);

        rtpListener.onAudioData(assemblyAIService::sendAudio);
        assemblyAIService.startSession(
                transcript -> processUserTranscript(conversationId, channelId, transcript),
                error -> handleTranscriptionError(conversationId, channelId, error),
                reason -> logger.info("[{}] AssemblyAI session closed. Reason: {}", conversationId, reason)
        );

        playWelcomeMessage(conversationId, channelId);
    }

    private void handleStasisEndEvent(JsonNode stasisEndEvent) {
        String channelId = stasisEndEvent.path("channel").path("id").asText();
        String conversationId = channelIdToConversationIdMap.get(channelId);
        if (conversationId != null) {
            logger.info("StasisEnd event received for channel {}. Cleaning up.", channelId);
            endCall(conversationId, channelId, "STASIS_END", false);
        }
    }

    private void processUserTranscript(String conversationId, String channelId, String userText) {
        logger.info("[{}] User said: '{}'", conversationId, userText);
        saveUserMessage(conversationId, userText);
        String assistantText = "Cevabınızı aldım: " + userText;
        logger.info("[{}] Sending assistant message: '{}'", conversationId, assistantText);
        saveAssistantMessage(conversationId, assistantText);
        ariConnectionManager.playAudio(channelId, "beep");
    }

    private void playWelcomeMessage(String conversationId, String channelId) {
        String welcomeText = "Merhaba, VitaNova Wellness'a hos geldiniz. Ben Elara, size nasil yardimci olabilirim?";
        saveAssistantMessage(conversationId, welcomeText);
        ariConnectionManager.playAudio(channelId, "hello-world");
    }

    private void endCall(String conversationId, String channelId, String status, boolean forceHangup) {
        if (channelIdToConversationIdMap.remove(channelId) != null) {
            logger.info("[{}] Cleaning up resources for channel {} with status: {}", conversationId, channelId, status);

            rtpListenerFactory.stopListener(conversationId);
            assemblyAIService.stopSession();
            //conversationService.endConversation(conversationId, status);

            if (forceHangup) {
                logger.warn("[{}] Forcing hangup on channel {} due to status: {}", conversationId, channelId, status);
                ariConnectionManager.hangupChannel(channelId);
            }
        }
    }

    private void handleTranscriptionError(String conversationId, String channelId, Exception error) {
        logger.error("[{}] Transcription error on channel {}: {}", conversationId, channelId, error.getMessage());
        endCall(conversationId, channelId, "ERROR", true);
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
}