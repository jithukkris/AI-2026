package com.healpay.ai;

/** Diagnoses a discrepancy and proposes one healing action. */
public interface DiagnosisService {

    AiDiagnosis diagnose(DiagnosisContext context);

    /** Shown in the UI next to each diagnosis: "claude" or "rules". */
    String sourceName();
}
