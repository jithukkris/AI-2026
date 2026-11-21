package com.healpay.repo;

import com.healpay.domain.LedgerTransaction;
import com.healpay.domain.ReconStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, Long> {

    Optional<LedgerTransaction> findByTxnRef(String txnRef);

    List<LedgerTransaction> findTop500ByOrderByCreatedAtDesc();

    List<LedgerTransaction> findTop50ByReconStatusOrderByCreatedAtDesc(ReconStatus status);

    long countByReconStatus(ReconStatus status);
}
