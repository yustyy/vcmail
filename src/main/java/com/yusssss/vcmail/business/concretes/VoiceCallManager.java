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
    private final AssemblyAIService assemblyAIService;
    private final AriConnectionManager ariConnectionManager;
    private final RtpListenerFactory rtpListenerFactory;


    private final Logger logger = LoggerFactory.getLogger(VoiceCallManager.class);


    public VoiceCallManager(ConversationService conversationService, AssemblyAIService assemblyAIService, AriConnectionManager ariConnectionManager, RtpListenerFactory rtpListenerFactory) {
        this.conversationService = conversationService;
        this.assemblyAIService = assemblyAIService;
        this.ariConnectionManager = ariConnectionManager;
        this.rtpListenerFactory = rtpListenerFactory;
    }

   @PostConstruct
    public void initialize(){
        logger.info("Initializing VoiceCallManager and listening for ARI events.");
        ariConnectionManager.onStatisStart(this::handleNewCall);
   }

    private void handleNewCall(JsonNode stasisStartEvent) {

        String channelId = stasisStartEvent.path("channel").path("id").asText();
        String callerNumber = stasisStartEvent.path("channel").path("caller").asText();

        logger.info("New call receiveed via ARI. Channel: {}, Caller: {}", channelId, callerNumber);


        Conversation conversation = conversationService.startConversation(callerNumber);
        String conversationId = conversation.getId();

        RtpListener rtpListener = rtpListenerFactory.createAndStartListener(conversationId);
        int listeningPort = rtpListener.getPort();

        ariConnectionManager.bridgeRtp(channelId, "127.0.0.1:"+listeningPort);


        rtpListener.onAudioData(audioBytes -> {
            assemblyAIService.sendAudio(audioBytes);
        });


        assemblyAIService.startSession(
                transcript -> processUserTranscript(conversationId, channelId, transcript),
                error -> handleTranscriptionError(conversationId, channelId, error),
                reason -> endCall(conversationId, channelId, "COMPLETED")
        );

        playWelcomeMessage(conversationId, channelId);


    }


    private void processUserTranscript(String conversationId, String channelId, String userText) {
        logger.info("[{}] User said: '{}'", conversationId, userText);

        saveUserMessage(conversationId, userText);

        String assistantText = "Cevabınızı aldım: " + userText;

        saveAssistantMessage(conversationId, assistantText);


        //TODO: implement openapi tts

        ariConnectionManager.playAudio(channelId, "beep");

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



    private void playWelcomeMessage(String conversationId, String channelId) {
        String welcomeText = "Merhaba, X'e hoşgeldiniz. Ben sanal asistanınızım. Size nasıl yardımcı olabilirim?";

        saveAssistantMessage(conversationId, welcomeText);


        //TODO: implement openapi tts
        ariConnectionManager.playAudio(channelId, "hello-world");
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
