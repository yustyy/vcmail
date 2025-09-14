package com.yusssss.vcmail.dataAccess;

import com.yusssss.vcmail.entities.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MessageDao extends JpaRepository<Message, String> {
    List<Message> findByConversation_IdOrderBySequenceIndexAsc(String conversartionId);

}
