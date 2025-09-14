package com.yusssss.vcmail.dataAccess;

import com.yusssss.vcmail.entities.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ConversationDao extends JpaRepository<Conversation, String> {
}
