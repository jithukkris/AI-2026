package com.healpay.repo;

import com.healpay.domain.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    List<AuditEvent> findByTxnRefOrderByOccurredAtAscIdAsc(String txnRef);

    List<AuditEvent> findTop40ByOrderByIdDesc();
}
