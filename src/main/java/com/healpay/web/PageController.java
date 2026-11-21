package com.healpay.web;

import com.healpay.ai.AssistantService;
import com.healpay.domain.Discrepancy;
import com.healpay.domain.DiscrepancyState;
import com.healpay.domain.ReconStatus;
import com.healpay.healing.SelfHealingOrchestrator;
import com.healpay.recon.ReconSummaryService;
import com.healpay.simulator.PaymentSimulator;
import com.healpay.simulator.Scenario;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/** Server-rendered UI (Thymeleaf). */
@Controller
public class PageController {

    private final TransactionQueryService queries;
    private final ReconSummaryService summaryService;
    private final SelfHealingOrchestrator orchestrator;
    private final PaymentSimulator simulator;
    private final AssistantService assistant;

    public PageController(TransactionQueryService queries, ReconSummaryService summaryService,
                          SelfHealingOrchestrator orchestrator, PaymentSimulator simulator,
                          AssistantService assistant) {
        this.queries = queries;
        this.summaryService = summaryService;
        this.orchestrator = orchestrator;
        this.simulator = simulator;
        this.assistant = assistant;
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        List<Discrepancy> active = queries.exceptions("ACTIVE");
        model.addAttribute("page", "dashboard");
        model.addAttribute("summary", summaryService.summary());
        model.addAttribute("events", queries.recentEvents());
        model.addAttribute("awaiting", active.stream()
                .filter(d -> d.getState() == DiscrepancyState.AWAITING_APPROVAL).limit(5).toList());
        model.addAttribute("aiSource", assistant.sourceName());
        return "dashboard";
    }

    @GetMapping("/transactions")
    public String transactions(@RequestParam(required = false) String q,
                               @RequestParam(required = false) ReconStatus status, Model model) {
        model.addAttribute("page", "transactions");
        model.addAttribute("transactions", queries.search(q, status));
        model.addAttribute("q", q == null ? "" : q);
        model.addAttribute("status", status);
        model.addAttribute("statuses", ReconStatus.values());
        return "transactions";
    }

    @GetMapping("/transactions/{txnRef}")
    public String transaction(@PathVariable String txnRef, Model model) {
        TransactionQueryService.TransactionDetail detail = queries.detail(txnRef)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found"));
        model.addAttribute("page", "transactions");
        model.addAttribute("d", detail);
        return "transaction";
    }

    @GetMapping("/exceptions")
    public String exceptions(@RequestParam(required = false, defaultValue = "ACTIVE") String state, Model model) {
        List<Discrepancy> list;
        try {
            list = queries.exceptions(state);
        } catch (IllegalArgumentException e) {
            list = queries.exceptions("ACTIVE");
            state = "ACTIVE";
        }
        model.addAttribute("page", "exceptions");
        model.addAttribute("exceptions", list);
        model.addAttribute("state", state.toUpperCase());
        model.addAttribute("states", List.of("ACTIVE", "AWAITING_APPROVAL", "ESCALATED", "WAITING", "RESOLVED", "REJECTED", "ALL"));
        return "exceptions";
    }

    @PostMapping("/exceptions/{id}/approve")
    public String approve(@PathVariable Long id, Principal principal, RedirectAttributes flash,
                          @RequestParam(required = false, defaultValue = "/exceptions") String back) {
        try {
            Discrepancy d = orchestrator.approve(id, principal.getName());
            flash.addFlashAttribute("flash", d.getState() == DiscrepancyState.RESOLVED
                    ? "Approved and healed exception #" + id + ". Records verified balanced."
                    : "Approved exception #" + id + " but verification failed - escalated.");
        } catch (RuntimeException e) {
            flash.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:" + safeBack(back);
    }

    @PostMapping("/exceptions/{id}/reject")
    public String reject(@PathVariable Long id, @RequestParam(required = false) String reason, Principal principal,
                         RedirectAttributes flash,
                         @RequestParam(required = false, defaultValue = "/exceptions") String back) {
        try {
            orchestrator.reject(id, principal.getName(), reason);
            flash.addFlashAttribute("flash", "Rejected exception #" + id + " - moved to manual handling.");
        } catch (RuntimeException e) {
            flash.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:" + safeBack(back);
    }

    @PostMapping("/admin/run")
    public String runNow(RedirectAttributes flash) {
        SelfHealingOrchestrator.CycleReport r = orchestrator.runCycle();
        flash.addFlashAttribute("flash", r.ran()
                ? "Cycle done: " + r.newDiscrepancies() + " new issue(s), " + r.autoHealed() + " auto-healed, "
                  + r.sentForApproval() + " sent for approval, " + r.resolvedByVerification() + " verified resolved."
                : "A cycle is already running - try again in a moment.");
        return "redirect:/";
    }

    @PostMapping("/admin/incidents")
    public String incidents(RedirectAttributes flash) {
        Map<Scenario, Integer> created = simulator.generateIncidents();
        flash.addFlashAttribute("flash", "Injected " + created.values().stream().mapToInt(Integer::intValue).sum()
                + " payments with incidents. Click \"Run healing now\" or wait for the next cycle.");
        return "redirect:/";
    }

    @GetMapping("/assistant")
    public String assistantPage(Model model) {
        model.addAttribute("page", "assistant");
        model.addAttribute("aiSource", assistant.sourceName());
        return "assistant";
    }

    /** Only allow redirects to our own pages. */
    private static String safeBack(String back) {
        return back != null && back.startsWith("/") && !back.startsWith("//") ? back : "/exceptions";
    }
}
