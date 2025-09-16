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
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class VoiceCallManager {

    private final ConversationService conversationService;
    private final AriConnectionManager ariConnectionManager;
    private final RtpListenerFactory rtpListenerFactory;
    private final AssemblyAIService assemblyAIService;

    private final Logger logger = LoggerFactory.getLogger(VoiceCallManager.class);


    private final Set<String> activeChannelIds = ConcurrentHashMap.newKeySet();
    private final Set<String> externalMediaChannels = ConcurrentHashMap.newKeySet();
    private final Set<String> activeConversations = ConcurrentHashMap.newKeySet();



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
        logger.info("Initializing VoiceCallManager and setting ARI event listener.");
        ariConnectionManager.onStasisStart(this::handleStasisEvent);
    }

    private void handleNewCall(String callerChannelId, String callerNumber) {
        logger.info("Processing new call. Channel: {}, Caller: {}", callerChannelId, callerNumber);

        activeChannelIds.add(callerChannelId);

        Conversation conversation = conversationService.startConversation();
        String conversationId = conversation.getId();
        activeConversations.add(conversationId);

        logger.info("Started new conversation {} for channel {}", conversationId, callerChannelId);

        String bridgeId = ariConnectionManager.createBridge();
        if (bridgeId == null) {
            logger.error("[{}] Could not create bridge. Ending call.", conversationId);
            cleanup(conversationId, callerChannelId);
            return;
        }

        ariConnectionManager.addChannelToBridge(bridgeId, callerChannelId);

        RtpListener rtpListener = rtpListenerFactory.createListener(conversationId);
        rtpListener.start();
        int listeningPort = rtpListener.getPort();

        JsonNode externalMediaChannel = ariConnectionManager.createExternalMediaChannel("172.19.0.1:" + listeningPort);
        if (externalMediaChannel == null) {
            logger.error("[{}] Could not create external media channel. Ending call.", conversationId);
            cleanup(conversationId, callerChannelId);
            return;
        }

        String mediaChannelId = externalMediaChannel.path("id").asText();
        externalMediaChannels.add(mediaChannelId);

        ariConnectionManager.addChannelToBridge(bridgeId, mediaChannelId);

        rtpListener.onAudioData(assemblyAIService::sendAudio);
        assemblyAIService.startSession(
                transcript -> processUserTranscript(conversationId, callerChannelId, transcript),
                error -> handleTranscriptionError(conversationId, callerChannelId, error),
                reason -> endCall(conversationId, callerChannelId, "COMPLETED")
        );

        playWelcomeMessage(conversationId, callerChannelId);
    }

    private void handleStasisEvent(JsonNode stasisStartEvent) {
        String channelId = stasisStartEvent.path("channel").path("id").asText();
        String channelName = stasisStartEvent.path("channel").path("name").asText();
        String callerNumber = stasisStartEvent.path("channel").path("caller").path("number").asText();

        logger.info("StasisStart event received. Channel: {}, Name: {}, Caller: {}",
                channelId, channelName, callerNumber);

        if (channelName != null && channelName.startsWith("UnicastRTP")) {
            logger.info("Ignoring StasisStart for external media channel: {}", channelId);
            externalMediaChannels.add(channelId);
            return;
        }

        if (activeChannelIds.contains(channelId)) {
            logger.warn("Received duplicate StasisStart event for already active channel {}. Ignoring.", channelId);
            return;
        }

        if (callerNumber == null || callerNumber.trim().isEmpty()) {
            logger.info("Ignoring StasisStart for channel without caller number: {}", channelId);
            return;
        }

        handleNewCall(channelId, callerNumber);
    }

    private void cleanup(String conversationId, String channelId) {
        activeChannelIds.remove(channelId);
        activeConversations.remove(conversationId);
        rtpListenerFactory.stopListener(conversationId);

        assemblyAIService.stopSession();

        ariConnectionManager.hangupChannel(channelId);
    }


    private void processUserTranscript(String conversationId, String channelId, String userText) {
        logger.info("[{}] User said: '{}'", conversationId, userText);

        saveUserMessage(conversationId, userText);


        String assistantText = "Cevabınızı aldım: " + userText;

        saveAssistantMessage(conversationId, assistantText);


        ariConnectionManager.playAudio(channelId, "beep");
    }

    private void playWelcomeMessage(String conversationId, String channelId) {
        String welcomeText = "Merhaba, VitaNova Wellness'a hoş geldiniz. Ben Elara, size nasıl yardımcı olabilirim?";
        saveAssistantMessage(conversationId, welcomeText);

        // TODO: TTS entegrasyonu
        ariConnectionManager.playAudio(channelId, "hello-world");
    }

    private void endCall(String conversationId, String channelId, String status) {
        logger.info("[{}] Ending call on channel {} with status: {}", conversationId, channelId, status);

        activeChannelIds.remove(channelId);


        rtpListenerFactory.stopListener(conversationId);
        assemblyAIService.stopSession();

        ariConnectionManager.hangupChannel(channelId);
    }

    private void handleTranscriptionError(String conversationId, String channelId, Exception error) {
        logger.error("[{}] Transcription error on channel {}: {}", conversationId, channelId, error.getMessage());
        endCall(conversationId, channelId, "ERROR");
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

    public void logActiveConnections() {
        logger.info("Active channels: {}", activeChannelIds.size());
        logger.info("Active conversations: {}", activeConversations.size());
        logger.info("External media channels: {}", externalMediaChannels.size());
    }
}