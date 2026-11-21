package com.healpay.web.api;

import com.healpay.domain.Discrepancy;
import com.healpay.healing.SelfHealingOrchestrator;
import com.healpay.web.TransactionQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/exceptions")
@Tag(name = "Exceptions", description = "Detected discrepancies, AI diagnoses and human approvals")
public class ExceptionApiController {

    private final TransactionQueryService queries;
    private final SelfHealingOrchestrator orchestrator;

    public ExceptionApiController(TransactionQueryService queries, SelfHealingOrchestrator orchestrator) {
        this.queries = queries;
        this.orchestrator = orchestrator;
    }

    public record RejectRequest(String reason) {}

    @GetMapping
    @Operation(summary = "List exceptions. state = ACTIVE (default), ALL, OPEN, WAITING, AWAITING_APPROVAL, ESCALATED, RESOLVED, REJECTED")
    public List<Discrepancy> list(@RequestParam(required = false, defaultValue = "ACTIVE") String state) {
        return queries.exceptions(state);
    }

    @GetMapping("/{id}")
    @Operation(summary = "One exception with its AI diagnosis and policy decision")
    public Discrepancy get(@PathVariable Long id) {
        return queries.exception(id).orElseThrow(() -> new IllegalArgumentException("Exception #" + id + " not found"));
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "ADMIN: approve the proposed fix (it is applied and verified immediately)")
    public Discrepancy approve(@PathVariable Long id, Principal principal) {
        return orchestrator.approve(id, principal.getName());
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "ADMIN: reject the proposed fix (exception moves to manual handling)")
    public Discrepancy reject(@PathVariable Long id, @RequestBody(required = false) RejectRequest body,
                              Principal principal) {
        return orchestrator.reject(id, principal.getName(), body == null ? null : body.reason());
    }
}
