package com.yusssss.vcmail.business.abstracts;

import com.yusssss.vcmail.entities.Message;

import java.util.List;

public interface MessageService {

    List<Message> getMessagesByConversationId(String conversartionId);


    Message save(Message message);

}
