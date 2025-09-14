package com.yusssss.vcmail.business.abstracts;

import com.yusssss.vcmail.entities.Conversation;
import com.yusssss.vcmail.entities.Message;

import java.util.List;

public interface ConversationService {

    List<Conversation> getAllConversations();

    Conversation getConversationById(String conversationId);

    Message addMessage(String conversationId, Message message);

    public Conversation startConversation();
}
