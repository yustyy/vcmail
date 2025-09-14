package com.yusssss.vcmail.business.concretes;

import com.yusssss.vcmail.business.abstracts.MessageService;
import com.yusssss.vcmail.dataAccess.MessageDao;
import com.yusssss.vcmail.entities.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class MessageManager implements MessageService {


    private final MessageDao messageDao;


    private final Logger logger = LoggerFactory.getLogger(MessageManager.class);


    public MessageManager(MessageDao messageDao) {
        this.messageDao = messageDao;
    }

    @Override
    public List<Message> getMessagesByConversationId(String conversartionId) {
        logger.info("Fetching messages for conversation with id: {}", conversartionId);
        return messageDao.findByConversation_IdOrderBySequenceIndexAsc(conversartionId);
    }

    @Override
    public Message save(Message message) {
        if (message.getTimestamp() == null) {
            message.setTimestamp(LocalDateTime.now());
        }
        logger.info("Saving new message for conversation id: {}", message.getConversation().getId());
        var savedMessage = messageDao.save(message);
        logger.info("Saved message with id: {}", savedMessage.getId());
        return savedMessage;
    }


}
