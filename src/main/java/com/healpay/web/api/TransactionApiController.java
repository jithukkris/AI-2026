package com.healpay.web.api;

import com.healpay.domain.LedgerTransaction;
import com.healpay.domain.ReconStatus;
import com.healpay.web.TransactionQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/transactions")
@Tag(name = "Transactions", description = "Search transactions and check their status")
public class TransactionApiController {

    private final TransactionQueryService queries;

    public TransactionApiController(TransactionQueryService queries) {
        this.queries = queries;
    }

    @GetMapping
    @Operation(summary = "Search transactions by reference / merchant / gateway ref, optionally by status")
    public List<LedgerTransaction> search(@RequestParam(required = false) String q,
                                          @RequestParam(required = false) ReconStatus status) {
        return queries.search(q, status);
    }

    @GetMapping("/{txnRef}")
    @Operation(summary = "Full status of one transaction: gateway records, bank credits, exceptions and timeline")
    public TransactionQueryService.TransactionDetail detail(@PathVariable String txnRef) {
        return queries.detail(txnRef)
                .orElseThrow(() -> new IllegalArgumentException("Transaction " + txnRef + " not found"));
    }
}
