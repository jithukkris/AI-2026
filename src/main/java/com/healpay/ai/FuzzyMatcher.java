package com.healpay.ai;

import java.util.Locale;

/**
 * Reference similarity for messy bank narrations, e.g.
 * narration "NEFT CR ACME PAY8F3KD92L SETTL" vs gateway ref "pay_8F3kd92LmQ".
 *
 * Score = longest common substring (alphanumeric, case-insensitive) / reference length.
 * Deterministic and explainable - the AI receives this score rather than guessing.
 */
public final class FuzzyMatcher {

    private FuzzyMatcher() {
    }

    public static double similarity(String narration, String... references) {
        if (narration == null) {
            return 0.0;
        }
        String hay = normalize(narration);
        double best = 0.0;
        for (String ref : references) {
            if (ref == null) {
                continue;
            }
            String needle = normalize(ref);
            if (needle.isEmpty()) {
                continue;
            }
            int lcs = longestCommonSubstring(hay, needle);
            // Ignore tiny accidental overlaps like "2024" or "PAY".
            if (lcs < 4) {
                continue;
            }
            best = Math.max(best, (double) lcs / needle.length());
        }
        return Math.min(1.0, best);
    }

    static String normalize(String s) {
        return s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    static int longestCommonSubstring(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        int max = 0;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                    max = Math.max(max, dp[i][j]);
                }
            }
        }
        return max;
    }
}
