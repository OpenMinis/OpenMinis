package com.openminis.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RulePatternTest {

    @Test
    fun allowPatternMatchesWholeCommandUnitsOnly() {
        assertTrue(RulePattern.allowMatches("git", "git status"))
        assertTrue(RulePattern.allowMatches("git", "git"))
        assertFalse(RulePattern.allowMatches("git", "grep category"))
        assertFalse(RulePattern.allowMatches("git", "gitz status"))
        assertTrue(RulePattern.allowMatches("cat*", "cat notes.md"))
        assertTrue(RulePattern.allowMatches("*", "anything at all"))
        assertFalse(RulePattern.allowMatches("", "git status"))
    }

    @Test
    fun questionMarkMatchesOneCharacter() {
        assertTrue(RulePattern.allowMatches("cat?", "cat1"))
        assertTrue(RulePattern.allowMatches("cat?", "cat1 --flag"))
        assertFalse(RulePattern.allowMatches("cat?", "cat"))
    }

    @Test
    fun denyMatchesFullStringOrLiteralFallback() {
        assertTrue(RulePattern.denyMatches("rm*", "rm -rf /"))
        assertTrue(RulePattern.denyMatches("*/etc/passwd*", "cat /etc/passwd"))
        assertFalse(RulePattern.denyMatches("rm*", "echo hello"))
    }

    @Test
    fun longStarPatternDoesNotBlowUp() {
        val pattern = "*a*a*a*a*a*a*a*a*a*a*b"
        val target = "a".repeat(16_000)
        // Over the target cap → deny fails closed; allow simply refuses.
        assertTrue(RulePattern.denyMatches(pattern, target))
        assertFalse(RulePattern.allowMatches(pattern, target))
    }

    @Test
    fun overlongTargetFailsClosedForDenyAndOpenForAllow() {
        val long = "x".repeat(RulePatternTestSupport.overCap)
        assertTrue(RulePattern.denyMatches("never-matches", long))
        assertFalse(RulePattern.allowMatches("x*", long))
    }
}

private object RulePatternTestSupport {
    // Mirrors the production cap; kept here so the test fails loudly if the
    // matcher silently starts accepting unbounded input.
    const val overCap = 9_000
}
