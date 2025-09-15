package com.yusssss.vcmail.webAPI;

import com.yusssss.vcmail.business.abstracts.ConversationService;
import com.yusssss.vcmail.business.abstracts.MessageService;
import com.yusssss.vcmail.core.utilities.results.SuccessDataResult;
import com.yusssss.vcmail.entities.Conversation;
import com.yusssss.vcmail.entities.Message;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversationService;
    private final MessageService messageService;


    public ConversationController(ConversationService conversationService, MessageService messageService) {
        this.conversationService = conversationService;
        this.messageService = messageService;
    }

    @GetMapping
    public ResponseEntity<SuccessDataResult<List<Conversation>>> getAllConversations() {

        List<Conversation> conversations = conversationService.getAllConversations();

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(new SuccessDataResult<>(conversations,
                        "Conversations fetched successfully",
                        HttpStatus.OK));

    }


    @GetMapping("/{conversationId}")
    public ResponseEntity<SuccessDataResult<Conversation>> getConversationById(@PathVariable String conversationId) {

        Conversation conversation = conversationService.getConversationById(conversationId);

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(new SuccessDataResult<>(conversation,
                        "Conversation fetched successfully",
                        HttpStatus.OK));

    }


    @GetMapping("/{conversationId}/messages")
    public ResponseEntity<SuccessDataResult<List<Message>>> getMessagesByConversationId(@PathVariable String conversationId) {

        List<Message> messages = messageService.getMessagesByConversationId(conversationId);

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(new SuccessDataResult<>(messages,
                        "Messages fetched successfully",
                        HttpStatus.OK));

    }



}
