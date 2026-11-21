package com.healpay.web.api;

import com.healpay.ai.AssistantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.healpay.events.EventBus;

@RestController
@RequestMapping("/api")
@Tag(name = "AI assistant & live events")
public class AssistantApiController {

    private final AssistantService assistant;
    private final EventBus eventBus;

    public AssistantApiController(AssistantService assistant, EventBus eventBus) {
        this.assistant = assistant;
        this.eventBus = eventBus;
    }

    public record AskRequest(String question) {}

    public record AskResponse(String answer, String source) {}

    @PostMapping("/assistant/ask")
    @Operation(summary = "Ask a question in plain language, e.g. \"Why is TXN-100003 not reconciled?\"")
    public AskResponse ask(@RequestBody AskRequest request) {
        String q = request == null || request.question() == null ? "" : request.question();
        if (q.length() > 500) {
            q = q.substring(0, 500);
        }
        return new AskResponse(assistant.ask(q), assistant.sourceName());
    }

    @GetMapping(path = "/events/stream", produces = "text/event-stream")
    @Operation(summary = "Server-Sent Events stream of the live audit trail")
    public SseEmitter stream() {
        return eventBus.subscribe();
    }
}
