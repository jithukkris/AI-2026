package com.healpay.events;

import com.healpay.domain.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Pushes audit events to connected browsers using Server-Sent Events,
 * so the dashboard and transaction pages update live.
 */
@Component
public class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        try {
            emitter.send(SseEmitter.event().name("hello").data(Map.of("message", "connected")));
        } catch (IOException e) {
            emitters.remove(emitter);
        }
        return emitter;
    }

    public void publish(AuditEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("txnRef", event.getTxnRef());
        payload.put("discrepancyId", event.getDiscrepancyId());
        payload.put("stage", event.getStage());
        payload.put("actor", event.getActor().name());
        payload.put("message", event.getMessage());
        payload.put("occurredAt", event.getOccurredAt().toString());
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("audit").data(payload));
            } catch (Exception e) {
                log.debug("Dropping SSE subscriber: {}", e.getMessage());
                emitters.remove(emitter);
            }
        }
    }

    public int subscriberCount() {
        return emitters.size();
    }
}
