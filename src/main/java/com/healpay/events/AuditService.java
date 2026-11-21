package com.healpay.events;

import com.healpay.domain.Actor;
import com.healpay.domain.AuditEvent;
import com.healpay.repo.AuditEventRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** Single entry point for writing the audit trail (and broadcasting it live). */
@Service
public class AuditService {

    private final AuditEventRepository repository;
    private final EventBus eventBus;

    public AuditService(AuditEventRepository repository, EventBus eventBus) {
        this.repository = repository;
        this.eventBus = eventBus;
    }

    public AuditEvent record(String txnRef, Long discrepancyId, String stage, Actor actor, String message) {
        return record(txnRef, discrepancyId, stage, actor, message, Instant.now());
    }

    public AuditEvent record(String txnRef, Long discrepancyId, String stage, Actor actor, String message, Instant at) {
        AuditEvent saved = repository.save(new AuditEvent(txnRef, discrepancyId, stage, actor, message, at));
        eventBus.publish(saved);
        return saved;
    }
}
