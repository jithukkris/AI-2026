package com.healpay.repo;

import com.healpay.domain.GatewayRecord;
import com.healpay.domain.GatewayStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface GatewayRecordRepository extends JpaRepository<GatewayRecord, Long> {

    List<GatewayRecord> findByTxnRefOrderByCapturedAtAsc(String txnRef);

    List<GatewayRecord> findByStatusAndSettledFalse(GatewayStatus status);

    @Query("select g.utr from GatewayRecord g where g.utr is not null")
    List<String> findAllUtrs();
}
