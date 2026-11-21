package com.healpay.web.api;

import com.healpay.healing.SelfHealingOrchestrator;
import com.healpay.recon.ReconSummaryService;
import com.healpay.simulator.PaymentSimulator;
import com.healpay.simulator.Scenario;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
@Tag(name = "Reconciliation", description = "Run the self-healing cycle, summary and demo data")
public class ReconApiController {

    private final SelfHealingOrchestrator orchestrator;
    private final ReconSummaryService summaryService;
    private final PaymentSimulator simulator;

    public ReconApiController(SelfHealingOrchestrator orchestrator, ReconSummaryService summaryService,
                              PaymentSimulator simulator) {
        this.orchestrator = orchestrator;
        this.summaryService = summaryService;
        this.simulator = simulator;
    }

    @GetMapping("/recon/summary")
    @Operation(summary = "Dashboard numbers: counts by status, auto-heal rate, amount at risk")
    public Map<String, Object> summary() {
        return summaryService.summary().asMap();
    }

    @PostMapping("/recon/run")
    @Operation(summary = "ADMIN: run one detect -> diagnose -> decide -> act -> verify cycle now")
    public SelfHealingOrchestrator.CycleReport run() {
        return orchestrator.runCycle();
    }

    @PostMapping("/simulator/incidents")
    @Operation(summary = "ADMIN: inject one of every incident type (plus two clean payments)")
    public Map<Scenario, Integer> incidents() {
        return simulator.generateIncidents();
    }

    @PostMapping("/simulator/seed")
    @Operation(summary = "ADMIN: generate a batch of mostly-clean traffic with some incidents")
    public Map<Scenario, Integer> seed(@RequestParam(defaultValue = "20") int count) {
        return simulator.seed(Math.max(1, Math.min(count, 500)));
    }
}
