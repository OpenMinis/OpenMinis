package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-skill-crlf] A SKILL.md written on Windows (CRLF line endings, sometimes a
 * UTF-8 BOM) must parse exactly like the same file with LF endings. iOS
 * imported such files as "Untitled Skill"; this pins the Android parser to the
 * same cases as MinisTests/Standalone/SkillMDLineEndingTests.swift.
 */
class SkillMdLineEndingTest {

    private val lf = "---\nname: Test Skill\ndescription: 测试技能\nversion: 1.0.0\n---\n# Body\n\nLine two\n"

    private fun parse(md: String) = SkillRepository.parseSkillMd(md)

    private fun assertParsed(md: String, name: String, description: String, version: String, body: String) {
        val p = parse(md)
        assertNotNull("expected frontmatter to parse", p)
        assertEquals(name, p!!.name)
        assertEquals(description, p.description)
        assertEquals(version, p.version)
        assertEquals(body, p.body)
    }

    @Test fun lf() = assertParsed(lf, "Test Skill", "测试技能", "1.0.0", "# Body\n\nLine two")

    @Test fun crlf() = assertParsed(lf.replace("\n", "\r\n"), "Test Skill", "测试技能", "1.0.0", "# Body\n\nLine two")

    @Test fun bareCr() = assertParsed(lf.replace("\n", "\r"), "Test Skill", "测试技能", "1.0.0", "# Body\n\nLine two")

    @Test fun crlfFoldedBlockScalar() = assertParsed(
        "---\nname: Block Skill\ndescription: >-\n  folded line one\n  folded line two\nversion: 2.0.0\n---\nBody\n"
            .replace("\n", "\r\n"),
        "Block Skill", "folded line one folded line two", "2.0.0", "Body",
    )

    @Test fun crlfLiteralBlockScalar() = assertParsed(
        "---\nname: Lit\ndescription: |\n  first\n  second\n---\nBody\n".replace("\n", "\r\n"),
        "Lit", "first\nsecond", "1.0.0", "Body",
    )

    @Test fun bomCrlf() = assertParsed(
        "﻿" + lf.replace("\n", "\r\n"), "Test Skill", "测试技能", "1.0.0", "# Body\n\nLine two",
    )

    @Test fun noFrontmatterStillRejected() {
        assertNull(parse("# Just markdown\r\n\r\nNo frontmatter here\r\n"))
    }
}
