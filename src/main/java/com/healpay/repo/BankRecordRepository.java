package com.healpay.repo;

import com.healpay.domain.BankRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BankRecordRepository extends JpaRepository<BankRecord, Long> {

    Optional<BankRecord> findFirstByUtr(String utr);

    Optional<BankRecord> findFirstByLinkedTxnRef(String txnRef);

    List<BankRecord> findByLinkedTxnRefIsNull();
}
