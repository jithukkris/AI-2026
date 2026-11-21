package com.healpay.repo;

import com.healpay.domain.Discrepancy;
import com.healpay.domain.DiscrepancyState;
import com.healpay.domain.DiscrepancyType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DiscrepancyRepository extends JpaRepository<Discrepancy, Long> {

    Optional<Discrepancy> findTopByTxnRefAndTypeOrderByCreatedAtDesc(String txnRef, DiscrepancyType type);

    Optional<Discrepancy> findTopByBankRecordIdAndTypeOrderByCreatedAtDesc(Long bankRecordId, DiscrepancyType type);

    List<Discrepancy> findByStateOrderByCreatedAtAsc(DiscrepancyState state);

    List<Discrepancy> findByStateInOrderByCreatedAtDesc(Collection<DiscrepancyState> states);

    List<Discrepancy> findByTxnRefOrderByCreatedAtDesc(String txnRef);

    List<Discrepancy> findByTxnRefAndStateIn(String txnRef, Collection<DiscrepancyState> states);

    List<Discrepancy> findTop200ByOrderByCreatedAtDesc();

    long countByState(DiscrepancyState state);

    long countByStateAndResolvedBy(DiscrepancyState state, com.healpay.domain.Actor resolvedBy);
}
