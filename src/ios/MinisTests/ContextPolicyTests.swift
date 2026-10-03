import XCTest

final class ContextPolicyTests: XCTestCase {

    // MARK: - Tier Classification

    func testTier_below32K() {
        let policy = ContextPolicy(contextWindow: 16_000)
        XCTAssertEqual(policy.offloadThreshold, 0, "< 32K: offload disabled")
        XCTAssertEqual(policy.offloadTarget, 0)
        XCTAssertEqual(policy.compactThreshold, 0, "< 32K: compact disabled")
        XCTAssertTrue(policy.exhaustedOnly)
        XCTAssertFalse(policy.manualCompactAllowed)
    }

    func testTier_exactly32K() {
        let policy = ContextPolicy(contextWindow: 32_000)
        XCTAssertEqual(policy.offloadThreshold, 22_000, "32K: offload at remaining ≤ 10K")
        XCTAssertEqual(policy.offloadTarget, 17_000)
        XCTAssertEqual(policy.compactThreshold, 0, "32K–64K: no auto-compact")
        XCTAssertTrue(policy.exhaustedOnly)
        XCTAssertTrue(policy.manualCompactAllowed)
    }

    func testTier_48K() {
        let policy = ContextPolicy(contextWindow: 48_000)
        XCTAssertEqual(policy.offloadThreshold, 38_000)
        XCTAssertEqual(policy.offloadTarget, 33_000)
        XCTAssertEqual(policy.compactThreshold, 0)
        XCTAssertTrue(policy.exhaustedOnly)
        XCTAssertTrue(policy.manualCompactAllowed)
    }

    func testTier_exactly64K() {
        let policy = ContextPolicy(contextWindow: 64_000)
        XCTAssertEqual(policy.offloadThreshold, 44_000, "64K: offload at remaining ≤ 20K")
        XCTAssertEqual(policy.offloadTarget, 34_000)
        XCTAssertEqual(policy.compactThreshold, 54_000, "64K: compact at remaining ≤ 10K")
        XCTAssertFalse(policy.exhaustedOnly)
        XCTAssertTrue(policy.manualCompactAllowed)
    }

    func testTier_100K() {
        let policy = ContextPolicy(contextWindow: 100_000)
        XCTAssertEqual(policy.offloadThreshold, 80_000)
        XCTAssertEqual(policy.offloadTarget, 70_000)
        XCTAssertEqual(policy.compactThreshold, 90_000)
        XCTAssertFalse(policy.exhaustedOnly)
    }

    func testTier_exactly128K() {
        let policy = ContextPolicy(contextWindow: 128_000)
        XCTAssertEqual(policy.offloadThreshold, 88_000, "128K: offload at remaining ≤ 40K")
        XCTAssertEqual(policy.offloadTarget, 68_000)
        XCTAssertEqual(policy.compactThreshold, 108_000, "128K: compact at remaining ≤ 20K")
        XCTAssertFalse(policy.exhaustedOnly)
    }

    func testTier_200K() {
        let policy = ContextPolicy(contextWindow: 200_000)
        XCTAssertEqual(policy.offloadThreshold, 160_000)
        XCTAssertEqual(policy.offloadTarget, 140_000)
        XCTAssertEqual(policy.compactThreshold, 180_000)
        XCTAssertFalse(policy.exhaustedOnly)
        XCTAssertTrue(policy.manualCompactAllowed)
    }

    func testTier_1M() {
        let policy = ContextPolicy(contextWindow: 1_000_000)
        XCTAssertEqual(policy.offloadThreshold, 960_000)
        XCTAssertEqual(policy.offloadTarget, 940_000)
        XCTAssertEqual(policy.compactThreshold, 980_000)
    }

    // MARK: - Boundary: Tier Edges

    func testBoundary_31999() {
        let policy = ContextPolicy(contextWindow: 31_999)
        XCTAssertEqual(policy.offloadThreshold, 0, "31999 is < 32K tier")
        XCTAssertTrue(policy.exhaustedOnly)
        XCTAssertFalse(policy.manualCompactAllowed)
    }

    func testBoundary_63999() {
        let policy = ContextPolicy(contextWindow: 63_999)
        XCTAssertEqual(policy.offloadThreshold, 53_999)
        XCTAssertEqual(policy.compactThreshold, 0)
        XCTAssertTrue(policy.exhaustedOnly)
    }

    func testBoundary_127999() {
        let policy = ContextPolicy(contextWindow: 127_999)
        XCTAssertEqual(policy.offloadThreshold, 107_999)
        XCTAssertEqual(policy.compactThreshold, 117_999)
        XCTAssertFalse(policy.exhaustedOnly)
    }

    // MARK: - check() Behavior

    func testCheck_ok_128Kplus() {
        let w = 200_000
        let policy = ContextPolicy(contextWindow: w)
        XCTAssertEqual(policy.check(estimatedTokens: 100_000, contextWindow: w), .ok)
    }

    func testCheck_needsCompact_128Kplus() {
        let w = 200_000
        let policy = ContextPolicy(contextWindow: w)
        XCTAssertEqual(policy.check(estimatedTokens: 180_000, contextWindow: w), .needsCompact)
        XCTAssertEqual(policy.check(estimatedTokens: 195_000, contextWindow: w), .needsCompact)
    }

    func testCheck_justBelowCompact_128Kplus() {
        let w = 200_000
        let policy = ContextPolicy(contextWindow: w)
        XCTAssertEqual(policy.check(estimatedTokens: 179_999, contextWindow: w), .ok)
    }

    func testCheck_exhausted_below32K() {
        let w = 16_000
        let policy = ContextPolicy(contextWindow: w)
        // exhaustedThreshold = 90% of 16K = 14_400
        XCTAssertEqual(policy.check(estimatedTokens: 14_400, contextWindow: w), .exhausted)
        XCTAssertEqual(policy.check(estimatedTokens: 15_000, contextWindow: w), .exhausted)
    }

    func testCheck_ok_below32K() {
        let w = 16_000
        let policy = ContextPolicy(contextWindow: w)
        XCTAssertEqual(policy.check(estimatedTokens: 10_000, contextWindow: w), .ok)
        XCTAssertEqual(policy.check(estimatedTokens: 14_399, contextWindow: w), .ok)
    }

    func testCheck_exhausted_32_64K() {
        let w = 48_000
        let policy = ContextPolicy(contextWindow: w)
        // exhaustedThreshold = offloadThreshold = 38_000
        XCTAssertEqual(policy.check(estimatedTokens: 38_000, contextWindow: w), .exhausted)
        XCTAssertEqual(policy.check(estimatedTokens: 47_000, contextWindow: w), .exhausted)
    }

    func testCheck_ok_32_64K() {
        let w = 48_000
        let policy = ContextPolicy(contextWindow: w)
        XCTAssertEqual(policy.check(estimatedTokens: 37_999, contextWindow: w), .ok)
    }

    func testCheck_64_128K_offloadZoneDoesNotTriggerCompact() {
        let w = 100_000
        let policy = ContextPolicy(contextWindow: w)
        // offloadThreshold = 80K, compactThreshold = 90K
        // At 85K: above offload, below compact → check returns .ok
        XCTAssertEqual(policy.check(estimatedTokens: 85_000, contextWindow: w), .ok)
        // At 90K: hits compact
        XCTAssertEqual(policy.check(estimatedTokens: 90_000, contextWindow: w), .needsCompact)
    }

    func testCheck_neverReturnsExhausted_for64Kplus() {
        for w in [64_000, 100_000, 128_000, 200_000, 1_000_000] {
            let policy = ContextPolicy(contextWindow: w)
            let almostFull = w - 1
            let result = policy.check(estimatedTokens: almostFull, contextWindow: w)
            XCTAssertNotEqual(result, .exhausted, "window=\(w) should not return .exhausted")
        }
    }

    // MARK: - shouldOffload()

    func testShouldOffload_disabled_below32K() {
        let policy = ContextPolicy(contextWindow: 16_000)
        XCTAssertFalse(policy.shouldOffload(estimatedTokens: 15_000))
        XCTAssertFalse(policy.shouldOffload(estimatedTokens: 16_000))
    }

    func testShouldOffload_triggers_32_64K() {
        let policy = ContextPolicy(contextWindow: 48_000)
        XCTAssertFalse(policy.shouldOffload(estimatedTokens: 37_999))
        XCTAssertTrue(policy.shouldOffload(estimatedTokens: 38_000))
        XCTAssertTrue(policy.shouldOffload(estimatedTokens: 45_000))
    }

    func testShouldOffload_triggers_128Kplus() {
        let policy = ContextPolicy(contextWindow: 200_000)
        XCTAssertFalse(policy.shouldOffload(estimatedTokens: 159_999))
        XCTAssertTrue(policy.shouldOffload(estimatedTokens: 160_000))
    }

    // MARK: - Hysteresis: Offload threshold → target gap prevents re-trigger

    func testOffloadHysteresis_32_64K() {
        let w = 48_000
        let policy = ContextPolicy(contextWindow: w)
        // After offloading to target (33K), should NOT re-trigger (threshold 38K)
        XCTAssertFalse(policy.shouldOffload(estimatedTokens: policy.offloadTarget),
                       "After offloading to target, should not immediately re-trigger")
        // Gap = 5K — even adding a few thousand tokens won't re-trigger
        XCTAssertFalse(policy.shouldOffload(estimatedTokens: policy.offloadTarget + 4_999))
    }

    func testOffloadHysteresis_64_128K() {
        let w = 100_000
        let policy = ContextPolicy(contextWindow: w)
        // Gap = 10K
        XCTAssertFalse(policy.shouldOffload(estimatedTokens: policy.offloadTarget))
        XCTAssertFalse(policy.shouldOffload(estimatedTokens: policy.offloadTarget + 9_999))
        XCTAssertTrue(policy.shouldOffload(estimatedTokens: policy.offloadTarget + 10_000))
    }

    func testOffloadHysteresis_128Kplus() {
        let w = 200_000
        let policy = ContextPolicy(contextWindow: w)
        // Gap = 20K
        XCTAssertFalse(policy.shouldOffload(estimatedTokens: policy.offloadTarget))
        XCTAssertFalse(policy.shouldOffload(estimatedTokens: policy.offloadTarget + 19_999))
        XCTAssertTrue(policy.shouldOffload(estimatedTokens: policy.offloadTarget + 20_000))
    }

    // MARK: - Compact after compact: summary still within threshold

    func testCompactNotRetriggered_whenSummaryBelowThreshold() {
        let w = 200_000
        let policy = ContextPolicy(contextWindow: w)
        // After compact, summary ~10K + kept messages ~20K = ~30K total
        // compactThreshold = 180K — way above, so no re-trigger
        XCTAssertEqual(policy.check(estimatedTokens: 30_000, contextWindow: w), .ok)
    }

    func testCompactNotRetriggered_evenLargeSummary() {
        let w = 128_000
        let policy = ContextPolicy(contextWindow: w)
        // compactThreshold = 108K
        XCTAssertEqual(policy.check(estimatedTokens: 100_000, contextWindow: w), .ok)
        // Only re-triggers at 108K
        XCTAssertEqual(policy.check(estimatedTokens: 108_000, contextWindow: w), .needsCompact)
    }

    // MARK: - Repeated Offload Protection (full scenario)

    func testRepeatedOffload_doesNotOscillate() {
        let w = 200_000
        let policy = ContextPolicy(contextWindow: w)
        // offloadThreshold = 160K, offloadTarget = 140K

        // Tokens at 165K → triggers offload
        XCTAssertTrue(policy.shouldOffload(estimatedTokens: 165_000))

        // After offload reduces to target (140K)
        XCTAssertFalse(policy.shouldOffload(estimatedTokens: 140_000))

        // Model generates ~5K more tokens per iteration
        XCTAssertFalse(policy.shouldOffload(estimatedTokens: 145_000))
        XCTAssertFalse(policy.shouldOffload(estimatedTokens: 150_000))
        XCTAssertFalse(policy.shouldOffload(estimatedTokens: 155_000))

        // Only re-triggers once gap is fully consumed
        XCTAssertTrue(policy.shouldOffload(estimatedTokens: 160_000))
    }

    func testRepeatedCompact_doesNotOscillate() {
        let w = 200_000
        let policy = ContextPolicy(contextWindow: w)
        // compactThreshold = 180K

        // At 185K → triggers compact
        XCTAssertEqual(policy.check(estimatedTokens: 185_000, contextWindow: w), .needsCompact)

        // After compact, summary + kept messages ~40K
        XCTAssertEqual(policy.check(estimatedTokens: 40_000, contextWindow: w), .ok)

        // Growth to 100K, still fine
        XCTAssertEqual(policy.check(estimatedTokens: 100_000, contextWindow: w), .ok)

        // Only re-triggers at 180K again
        XCTAssertEqual(policy.check(estimatedTokens: 179_999, contextWindow: w), .ok)
        XCTAssertEqual(policy.check(estimatedTokens: 180_000, contextWindow: w), .needsCompact)
    }

    // MARK: - End-to-End: simulated token counts → policy decisions

    func testEndToEnd_128K_belowAllThresholds() {
        let w = 128_000
        let policy = ContextPolicy(contextWindow: w)
        let tokens = 50_000
        XCTAssertEqual(policy.check(estimatedTokens: tokens, contextWindow: w), .ok)
        XCTAssertFalse(policy.shouldOffload(estimatedTokens: tokens))
    }

    func testEndToEnd_128K_triggersOffloadNotCompact() {
        let w = 128_000
        let policy = ContextPolicy(contextWindow: w)
        // 92K: above offload (88K), below compact (108K)
        let tokens = 92_000
        XCTAssertTrue(policy.shouldOffload(estimatedTokens: tokens))
        XCTAssertEqual(policy.check(estimatedTokens: tokens, contextWindow: w), .ok,
                       "Offload zone doesn't trigger compact")
    }

    func testEndToEnd_128K_triggersCompact() {
        let w = 128_000
        let policy = ContextPolicy(contextWindow: w)
        let tokens = 110_000
        XCTAssertEqual(policy.check(estimatedTokens: tokens, contextWindow: w), .needsCompact)
    }

    func testEndToEnd_16K_triggersExhausted() {
        let w = 16_000
        let policy = ContextPolicy(contextWindow: w)
        let tokens = 15_000
        XCTAssertEqual(policy.check(estimatedTokens: tokens, contextWindow: w), .exhausted)
    }

    func testEndToEnd_48K_triggersExhausted() {
        let w = 48_000
        let policy = ContextPolicy(contextWindow: w)
        let tokens = 40_000
        XCTAssertEqual(policy.check(estimatedTokens: tokens, contextWindow: w), .exhausted)
    }

    // MARK: - Zero / Edge Cases

    func testZeroContextWindow() {
        let policy = ContextPolicy(contextWindow: 0)
        XCTAssertEqual(policy.offloadThreshold, 0)
        XCTAssertTrue(policy.exhaustedOnly)
        // 90% of 0 = 0 → any usage triggers exhausted
        XCTAssertEqual(policy.check(estimatedTokens: 0, contextWindow: 0), .exhausted)
    }

    func testVerySmallWindow_8K() {
        let policy = ContextPolicy(contextWindow: 8_192)
        XCTAssertEqual(policy.offloadThreshold, 0, "8K: no offload")
        XCTAssertEqual(policy.compactThreshold, 0, "8K: no compact")
        XCTAssertTrue(policy.exhaustedOnly)
        // 90% of 8192 = 7372
        XCTAssertEqual(policy.check(estimatedTokens: 7_372, contextWindow: 8_192), .exhausted)
        XCTAssertEqual(policy.check(estimatedTokens: 7_371, contextWindow: 8_192), .ok)
    }

    // MARK: - [T-ctx-user-cap] User-chosen group caps

    /// The reported bug: a 32K group cap landed in the 32K–64K tier, whose
    /// `compactThreshold = 0` made `.needsCompact` unreachable, so the session
    /// ran past the cap with no compaction and no block.
    func testUserCap_32K_autoCompactIsAvailable() {
        let policy = ContextPolicy(contextWindow: 32_000, isUserCap: true)
        XCTAssertEqual(policy.compactThreshold, 27_200, "85% of 32K")
        XCTAssertEqual(policy.offloadThreshold, 22_400, "70% of 32K")
        XCTAssertEqual(policy.offloadTarget, 17_600, "55% of 32K")
        XCTAssertFalse(policy.exhaustedOnly)
        XCTAssertTrue(policy.manualCompactAllowed)
        XCTAssertEqual(policy.check(estimatedTokens: 27_200, contextWindow: 32_000), .needsCompact)
        XCTAssertEqual(policy.check(estimatedTokens: 27_199, contextWindow: 32_000), .ok)
    }

    /// Same window, but as a model's NATIVE size — the old tier must be intact,
    /// because a genuinely small model still cannot pay for a summary.
    func testNativeWindow_32K_keepsLegacyTier() {
        let policy = ContextPolicy(contextWindow: 32_000)
        XCTAssertEqual(policy.compactThreshold, 0, "native 32K: unchanged, no auto-compact")
        XCTAssertTrue(policy.exhaustedOnly)
    }

    func testUserCap_128K_proportional() {
        let policy = ContextPolicy(contextWindow: 128_000, isUserCap: true)
        XCTAssertEqual(policy.compactThreshold, 108_800, "85% of 128K")
        XCTAssertEqual(policy.check(estimatedTokens: 108_800, contextWindow: 128_000), .needsCompact)
    }

    /// The screenshot case: 138.8k used against a 128k cap = 108%.
    func testUserCap_128K_overCapCompacts() {
        let policy = ContextPolicy(contextWindow: 128_000, isUserCap: true)
        XCTAssertEqual(policy.check(estimatedTokens: 138_800, contextWindow: 128_000), .needsCompact)
    }

    // MARK: - [T-ctx-overflow-hard-stop] Past the ceiling

    /// Previously `.ok`: an exhausted-only tier answered `.ok` right up to its
    /// advisory line and kept answering `.ok` past 100%, so oversized requests
    /// went out anyway.
    func testOverflow_exhaustedOnlyTier_compactsWhenItCan() {
        let policy = ContextPolicy(contextWindow: 48_000)
        XCTAssertEqual(policy.compactThreshold, 0, "precondition: tier has auto-compact off")
        XCTAssertTrue(policy.manualCompactAllowed, "precondition: a summary is viable here")
        XCTAssertEqual(policy.check(estimatedTokens: 48_000, contextWindow: 48_000), .needsCompact)
        XCTAssertEqual(policy.check(estimatedTokens: 60_000, contextWindow: 48_000), .needsCompact)
    }

    /// Below 32K a summary is not viable at all, so overflow must report
    /// exhausted (prompt the user) rather than attempt a compaction.
    func testOverflow_tinyWindow_reportsExhausted() {
        let policy = ContextPolicy(contextWindow: 8_192)
        XCTAssertFalse(policy.manualCompactAllowed, "precondition: compaction not viable")
        XCTAssertEqual(policy.check(estimatedTokens: 9_000, contextWindow: 8_192), .exhausted)
    }

    /// Overflow handling must not disturb the normal large-window path.
    func testOverflow_largeWindow_stillCompactsViaThreshold() {
        let policy = ContextPolicy(contextWindow: 200_000)
        XCTAssertEqual(policy.check(estimatedTokens: 180_000, contextWindow: 200_000), .needsCompact)
        XCTAssertEqual(policy.check(estimatedTokens: 250_000, contextWindow: 200_000), .needsCompact)
        XCTAssertEqual(policy.check(estimatedTokens: 179_999, contextWindow: 200_000), .ok)
    }
}
