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

@Service
public class VoiceCallManager {

    private final ConversationService conversationService;
    private final AriConnectionManager ariConnectionManager;
    private final RtpListenerFactory rtpListenerFactory;
    private final AssemblyAIService assemblyAIService;

    private final Logger logger = LoggerFactory.getLogger(VoiceCallManager.class);


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
        ariConnectionManager.onStasisStart(this::handleNewCall);
    }

    private void handleNewCall(JsonNode stasisStartEvent) {
        String callerChannelId = stasisStartEvent.path("channel").path("id").asText();
        String callerNumber = stasisStartEvent.path("channel").path("caller").path("number").asText();
        logger.info("New call received via ARI. Channel: {}, Caller: {}", callerChannelId, callerNumber);

        Conversation conversation = conversationService.startConversation();
        String conversationId = conversation.getId();

        String bridgeId = ariConnectionManager.createBridge();
        if (bridgeId == null) {
            logger.error("[{}] Could not create a bridge. Ending call.", conversationId);
            ariConnectionManager.hangupChannel(callerChannelId);
            return;
        }

        ariConnectionManager.addChannelToBridge(bridgeId, callerChannelId);


        RtpListener rtpListener = rtpListenerFactory.createListener(conversationId);
        rtpListener.start();
        int listeningPort = rtpListener.getPort();


        JsonNode externalMediaChannel = ariConnectionManager.createExternalMediaChannel("172.19.0.1:" + listeningPort);
        if (externalMediaChannel == null) {
            logger.error("[{}] Could not create external media channel. Ending call.", conversationId);
            ariConnectionManager.hangupChannel(callerChannelId);
            return;
        }
        String mediaChannelId = externalMediaChannel.path("id").asText();

        ariConnectionManager.addChannelToBridge(bridgeId, mediaChannelId);

        rtpListener.onAudioData(assemblyAIService::sendAudio);
        assemblyAIService.startSession(
                transcript -> processUserTranscript(conversationId, callerChannelId, transcript),
                error -> handleTranscriptionError(conversationId, callerChannelId, error),
                reason -> endCall(conversationId, callerChannelId, "COMPLETED")
        );

        playWelcomeMessage(conversationId, callerChannelId);
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
}