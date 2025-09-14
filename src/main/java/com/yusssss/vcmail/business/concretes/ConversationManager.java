package com.yusssss.vcmail.business.concretes;

import com.yusssss.vcmail.business.abstracts.ConversationService;
import com.yusssss.vcmail.business.abstracts.MessageService;
import com.yusssss.vcmail.core.exceptions.ResourceNotFoundException;
import com.yusssss.vcmail.dataAccess.ConversationDao;
import com.yusssss.vcmail.entities.Conversation;
import com.yusssss.vcmail.entities.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class ConversationManager implements ConversationService {

    private final ConversationDao conversationDao;

    private final Logger logger = LoggerFactory.getLogger(ConversationManager.class);
    private final MessageService messageService;


    public ConversationManager(ConversationDao conversationDao, MessageService messageService) {
        this.conversationDao = conversationDao;
        this.messageService = messageService;
    }

    @Override
    public List<Conversation> getAllConversations() {
        logger.info("Fetching all conversations;");
        return conversationDao.findAll();
    }

    @Override
    public Conversation getConversationById(String conversationId) {
        logger.info("Fetching conversation with id: {}", conversationId);
        Conversation conversation = conversationDao.findById(conversationId)
                .orElseThrow(() -> {
                    logger.info("Conversation not found with id: {}", conversationId);
                    return new ResourceNotFoundException("Conversation not found with id: " + conversationId);
                });

        logger.info("Found conversation with id: {}", conversationId);

        return conversation;
    }

    @Override
    public Message addMessage(String conversationId, Message message) {
        logger.info("Adding message to conversation with id: {}", conversationId);
        Conversation conversation = getConversationById(conversationId);

        message.setConversation(conversation);

        var savedMessage = messageService.save(message);

        return savedMessage;
    }

    @Override
    public Conversation startConversation() {
        logger.info("Starting a new conversation");
        Conversation conversation = new Conversation();
        conversation.setStartTime(LocalDateTime.now());
        conversation.setStatus("IN_PROGRESS");
        var savedConversation = conversationDao.save(conversation);
        logger.info("Started a new conversation with id: {}", savedConversation.getId());
        return savedConversation;
    }
}
