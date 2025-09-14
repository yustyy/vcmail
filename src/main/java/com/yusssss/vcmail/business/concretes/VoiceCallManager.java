package com.yusssss.vcmail.business.concretes;

import com.yusssss.vcmail.business.abstracts.ConversationService;
import com.yusssss.vcmail.business.abstracts.MessageService;
import com.yusssss.vcmail.business.abstracts.VoiceCallService;
import com.yusssss.vcmail.core.utilities.assemblyAI.AssemblyAIService;
import com.yusssss.vcmail.entities.Conversation;
import com.yusssss.vcmail.entities.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class VoiceCallManager implements VoiceCallService {

    private final ConversationService conversationService;

    private final MessageService messageService;

    private final Logger logger = LoggerFactory.getLogger(VoiceCallManager.class);
    private final AssemblyAIService assemblyAIService;


    public VoiceCallManager(ConversationService conversationService, MessageService messageService, AssemblyAIService assemblyAIService) {
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.assemblyAIService = assemblyAIService;
    }

    @Override
    public void handleIncomingCall() {
        logger.info("Handling incoming voice call...");

        Conversation conversation = conversationService.startConversation();
        String conversationId = conversation.getId();

        try{
            setupAndStartTranscriptionStream(conversationId);




        }catch (Exception e){
            logger.error("Error while setting up and starting voice call. Error: {} ConversationID: {}", e, conversationId);
            endCall(conversationId, "ERROR");
        }
    }

    private void endCall(String conversationId, String error) {
        logger.info("Ending voice call for conversation ID: {}, error: {}", conversationId, error);

        assemblyAIService.stopSession();

    }

    private void playWelcomeMessage(String conversationId) {
        logger.info("Playing welcome message for conversation id: {}", conversationId);

        String welcomeText = "Merhaba, X'e hoş geldiniz. Ben sanal asistanım, size nasıl yardımcı olabilirim?";

        logger.info("[{}] Playing welcome message: '{}'", conversationId, welcomeText);

        Message welcomeMessage = new Message();
        welcomeMessage.setSpeaker("ASSISTANT");
        welcomeMessage.setText(welcomeText);
        conversationService.addMessage(conversationId, welcomeMessage);



        logger.info("Welcome message played for conversation id: {}", conversationId);
    }

    private void setupAndStartTranscriptionStream(String conversationId) {
        logger.info("Setting up transcription stream for conversation id: {}", conversationId);

        assemblyAIService.startSession(
                transcript -> processUserTranscript(conversationId, transcript),
                error -> handleTranscriptionError(conversationId, error),
                reason -> endCall(conversationId, "COMPLETED")
        );


    }

    private void handleTranscriptionError(String conversationId, Exception error) {
        logger.error("Transcription error for conversation id: {}: {}", conversationId, error.getMessage());
        endCall(conversationId, "ERROR");

    }

    private void processUserTranscript(String conversationId, String transcript) {
        logger.info("Processing user transcript for conversation id: {}", conversationId);

        Message userMessage = new Message();
        userMessage.setSpeaker("USER");
        userMessage.setText(transcript);
        conversationService.addMessage(conversationId, userMessage);

        String assistantText = "Cevabınızı aldım: " + transcript;

        logger.info("Assistant will say: {}", assistantText);

        Message assistantMessage = new Message();
        assistantMessage.setSpeaker("ASSISTANT");
        assistantMessage.setText(assistantText);
        conversationService.addMessage(conversationId, assistantMessage);
    }
}
