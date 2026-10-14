package com.openminis.app.sandbox

import com.openminis.app.sandbox.kernel.BudgetClassifier
import com.openminis.app.sandbox.kernel.GuardianScript
import com.openminis.app.sandbox.kernel.WorkClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestWorkloadPolicyTest {

    private val incident =
        "python3 audit_openminis_comprehensive.py 2>&1 | tee /tmp/audit_report.txt | head -150"

    @Test
    fun wrapRunsThePipelineOnceInTheSameShell() {
        val budget = BudgetClassifier.classify(incident)
        val wrapped = GuestLimits.wrap(incident)
        val groupKill = "kill -TERM -" + "$" + "$"
        assertTrue(wrapped.contains("ulimit -S -t ${budget.cpuSeconds}"))
        assertTrue(wrapped.contains("ulimit -S -u ${budget.nproc}"))
        // Hard cpu/nproc/fsize all carry the headroom multiplier — see
        // GuardianScript.HARD_HEADROOM. Referencing the constant rather than a
        // literal 4 keeps the two from drifting.
        assertTrue(wrapped.contains("ulimit -H -t ${budget.cpuSeconds * GuardianScript.HARD_HEADROOM}"))
        assertTrue(wrapped.contains("ulimit -H -u ${budget.nproc * GuardianScript.HARD_HEADROOM}"))
        assertTrue(wrapped.contains("ulimit -S -f ${budget.fileBlocks()}"))
        assertTrue(wrapped.contains("ulimit -H -f ${budget.fileBlocks() * GuardianScript.HARD_HEADROOM}"))
        assertTrue(wrapped.contains("trap 'kill -KILL"))
        assertTrue(wrapped.contains(groupKill))
        assertFalse(wrapped.contains("kill -0"))
        assertFalse(wrapped.contains("|| nice"))
        assertFalse(wrapped.contains("|| ionice"))
        assertEquals(1, wrapped.split(incident).size - 1)
    }

    @Test
    fun persistentCommandIsNotASubshellAndHasNoTrap() {
        val script = GuardianScript.persistentCommand("echo hi | head", 60)
        val groupKill = "kill -TERM -" + "$" + "$"
        assertTrue(script.contains(groupKill))
        assertFalse(script.contains("trap"))
        assertFalse(script.contains("ulimit"))
        assertFalse(script.contains("kill -0"))
        assertFalse(script.contains("( echo hi"))
        assertEquals(1, script.split("echo hi | head").size - 1)
    }

    @Test
    fun pythonRequestCannotRaiseTheCeiling() {
        assertEquals(600_000L, ShellTimeoutPolicy.effectiveMs(incident, 600_000L))
        assertEquals(600_000L, ShellTimeoutPolicy.effectiveMs(incident, 10_000_000L))
        assertEquals(600_000L, BudgetClassifier.classify(incident).wallMs)
        assertEquals(WorkClass.NORMAL, BudgetClassifier.classify(incident).workClass)
    }

    @Test
    fun setupFloorSurvivesAShortRequest() {
        val cmd = "minis-dev-setup-full"
        assertEquals(
            ShellTimeoutPolicy.LONG_RUNNING_TIMEOUT_MS,
            ShellTimeoutPolicy.effectiveMs(cmd, 60_000L),
        )
    }

    /**
     * [T-setup-family] The Dev Suite installer runs
     * `/usr/local/bin/minis-setup-<tool>` through ExecutionCoordinator with a
     * documented 10–30 minute build/unpack and a 30-minute request. Before the
     * fix the family matched neither isSetup nor the "minis-dev-setup" string,
     * classified NORMAL (600s wall), and armMs capped the caller's 30 minutes
     * to 10 — every llama.cpp build / Flutter unpack died at ~10 min and the
     * component was marked FAILED.
     */
    @Test
    fun setupFamilyKeepsAThirtyMinuteWall() {
        val cmd = "/usr/local/bin/minis-setup-llama-cpp"
        val thirtyMin = 30 * 60_000L
        assertTrue(GuestWorkloadPolicy.isSetup(cmd))
        assertEquals(WorkClass.SETUP, BudgetClassifier.classify(cmd).workClass)
        // A 30-minute request is honoured, not capped to the NORMAL wall.
        assertEquals(thirtyMin, BudgetClassifier.armMs(cmd, thirtyMin))
        // The SETUP floor survives a short request, same as the named scripts.
        assertEquals(
            ShellTimeoutPolicy.LONG_RUNNING_TIMEOUT_MS,
            ShellTimeoutPolicy.effectiveMs(cmd, 60_000L),
        )
        // The whole family shares one predicate — a future minis-setup-* is
        // setup-class the moment it ships, and takes the host apt mutex.
        for (tool in listOf("flutter", "jadx", "rust", "pnpm", "qemu")) {
            assertTrue("minis-setup-$tool", GuestWorkloadPolicy.isSetup("/usr/local/bin/minis-setup-$tool"))
            assertTrue("apt mutex for minis-setup-$tool", SandboxResourceGate.isPackageManager("minis-setup-$tool"))
        }
    }

    @Test
    fun findIsNormalAndACallerMayShortenIt() {
        // Class ceiling: a broad find walk is NORMAL (600s), not INTERACTIVE
        // — slowness is handled by the wall clock, not by refusing reads.
        assertEquals(600_000L, ShellTimeoutPolicy.effectiveMs("find /var/minis/workspace -name '*.kt'", 0L))
        // [T-timeout-honored] a caller may now SHORTEN that wall…
        assertEquals(60_000L, ShellTimeoutPolicy.effectiveMs("find /var/minis/workspace -name '*.kt'", 60_000L))
        assertEquals(30_000L, ShellTimeoutPolicy.effectiveMs("find /data -name '*.db'", 30_000L))
        // …but never extend past it.
        assertEquals(600_000L, ShellTimeoutPolicy.effectiveMs("find /data -name '*.db'", 10_000_000L))
        // A broad walk is slow, not irreversible. It is no longer refused
        // outright — refusing a read-only command in every mode made it
        // unavailable. The wall clock, output rate and resident window brake
        // it instead, and the disk gate still refuses it under pressure.
        assertNull(GuestWorkloadPolicy.hostRefusal("find /data -name '*.db'"))
    }

    @Test
    fun diskPressureRefusesTheIncidentAndStillAllowsLs() {
        val total = 213L * 1024 * 1024 * 1024
        // [T-disk-percent-arm] stress is ABSOLUTE free bytes now: 1 GiB free
        // is stressed on any volume; 83% used with 35 GiB free is not.
        val stressed = 1L * 1024 * 1024 * 1024
        assertTrue(GuestWorkloadPolicy.diskRefusal(stressed, total, incident)!!.contains("exit 126"))
        assertNull(GuestWorkloadPolicy.diskRefusal(stressed, total, "ls /tmp"))
        // The reported field case: ~90% used, tens of GB free — must run.
        val ninetyPercentUsedFree20Gb = 20L * 1024 * 1024 * 1024
        assertNull(
            "90% used with 20 GiB free must not be refused",
            GuestWorkloadPolicy.diskRefusal(ninetyPercentUsedFree20Gb, total, incident),
        )
        val healthy = 100L * 1024 * 1024 * 1024
        assertNull(GuestWorkloadPolicy.diskRefusal(healthy, total, incident))
        assertTrue(GuestWorkloadPolicy.diskRefusal(stressed, total, "./not-a-known-tool --bomb")!!.contains("exit 126"))
        assertTrue(GuestWorkloadPolicy.diskRefusal(stressed, total, "echo hi > /tmp/x")!!.contains("exit 126"))
        assertNull(GuestWorkloadPolicy.diskRefusal(stressed, total, "echo hi"))
        assertNull(GuestWorkloadPolicy.diskRefusal(stressed, total, "git status"))
    }

    /**
     * [T-devnull-redirect] The exact command from the field report:
     * `du -xh --max-depth=2 / 2>/dev/null | sort -rh | head -30` was refused
     * under disk stress twice over — `du` was missing from the read-only
     * tokens, and `2>/dev/null` matched the write-redirect regex. A discarded
     * stream is not a write, and `du` is the one tool that finds what to
     * clean.
     */
    @Test
    fun duPipelineWithDevNullRedirectIsReadOnly() {
        val reported = "du -xh --max-depth=2 / 2>/dev/null | sort -rh | head -30"
        assertTrue(GuestWorkloadPolicy.isObviouslyReadOnly(reported))
        assertTrue(GuestWorkloadPolicy.isObviouslyReadOnly("du -sh /var/minis"))
        assertTrue(GuestWorkloadPolicy.isObviouslyReadOnly("cat /etc/hosts 2>/dev/null"))
        assertTrue(GuestWorkloadPolicy.isObviouslyReadOnly("df -h 2>/dev/null | head -5"))
        val total = 213L * 1024 * 1024 * 1024
        val stressed = 1L * 1024 * 1024 * 1024
        assertNull(GuestWorkloadPolicy.diskRefusal(stressed, total, reported))
        // Redirects to REAL files are still writes, with or without a
        // /dev/null arm in the same command line.
        assertFalse(GuestWorkloadPolicy.isObviouslyReadOnly("cat a > /tmp/out"))
        assertFalse(GuestWorkloadPolicy.isObviouslyReadOnly("cat a >> /tmp/out 2>/dev/null"))
        assertFalse(GuestWorkloadPolicy.isObviouslyReadOnly("cat a 1>/dev/null; tee /tmp/x"))
        assertTrue(GuestWorkloadPolicy.isObviouslyReadOnly("echo \"2>/dev/null\""))
    }

    /**
     * [T-cd-readonly] `cd` is pure navigation — it writes nothing. Its
     * absence from READ_ONLY_TOKENS made every `cd repo && grep …` classify
     * as heavy work and arm the 1200s BATCH wall (the field report).
     * [T-quote-aware-split] A `|` inside quotes must not create a phantom
     * segment either: `grep -n "a\|b"` stayed read-only in truth but failed
     * the whitelist on its garbage split half.
     */
    @Test
    fun cdPrefixedAndQuotedPipePipelinesAreReadOnly() {
        assertTrue(GuestWorkloadPolicy.isObviouslyReadOnly("cd /var/minis/workspace/repo && grep -rn foo src/ | head"))
        assertTrue(GuestWorkloadPolicy.isObviouslyReadOnly("cd /tmp && ls -la"))
        assertTrue(GuestWorkloadPolicy.isObviouslyReadOnly("grep -n \"a\\|b\" file"))
        assertTrue(GuestWorkloadPolicy.isObviouslyReadOnly("echo \"a ; b\""))
        // Real writes inside quotes stay writes (hasWriteSyntax already
        // stripped quotes); real pipes still segment.
        assertFalse(GuestWorkloadPolicy.isObviouslyReadOnly("cd /tmp && rm -rf x"))
        assertFalse(GuestWorkloadPolicy.isObviouslyReadOnly("echo \"x\" > /tmp/out"))
        // The split helper itself: quoted spans blanked, no phantom halves.
        // Whitespace is collapsed for the assertion — the replacement leaves
        // the surrounding spaces, which is irrelevant to token extraction.
        assertEquals(
            listOf("grep -n file"),
            GuestWorkloadPolicy.splitCommandSegments("grep -n \"a\\|b\" file")
                .map { it.replace(Regex("\\s+"), " ") },
        )
        assertEquals(
            listOf("cd /x", "ls"),
            GuestWorkloadPolicy.splitCommandSegments("cd /x && ls"),
        )
    }

    /**
     * [T-disk-percent-arm] The refusal message says "先清理空间再重试", but
     * the gate used to refuse `rm` and `apt-get clean` under the same
     * stress — the cleanup path was blocked by the very gate demanding it.
     */
    @Test
    fun diskReliefCommandsRunUnderStress() {
        val total = 213L * 1024 * 1024 * 1024
        val stressed = 1L * 1024 * 1024 * 1024
        for (cmd in listOf(
            "rm -rf /var/minis/workspace/build",
            "rm -rf /tmp/x; echo done",
            "apt-get clean",
            "apt-get autoremove -y",
            "pip cache purge",
            "npm cache clean --force",
            "truncate -s 0 /var/log/big.log",
        )) {
            assertNull("cleanup must run under stress: $cmd", GuestWorkloadPolicy.diskRefusal(stressed, total, cmd))
        }
        // Growth is still refused under stress.
        assertTrue(GuestWorkloadPolicy.diskRefusal(stressed, total, "truncate -s 4G /tmp/big")!!.contains("exit 126"))
        assertTrue(GuestWorkloadPolicy.diskRefusal(stressed, total, "apt-get install -y curl")!!.contains("exit 126"))
        // Root deletion stays refused by its OWN gate, ahead of the disk gate.
        assertNotNull(GuestWorkloadPolicy.hostRefusal("rm -rf /"))
    }

    @Test
    fun escapedChildStaysOwnedUntilItExits() {
        val tree = StickyTree(7254)
        val seen = tree.observe(setOf(7254, 8988, 8989), setOf(7254, 8988, 8989))
        assertEquals(setOf(7254, 8988, 8989), seen)
        val escaped = tree.observe(setOf(7254), setOf(7254, 8988))
        assertTrue(escaped.contains(8988))
        val dead = tree.observe(setOf(7254), setOf(7254))
        assertFalse(dead.contains(8988))
    }

    @Test
    fun processCapTripsOneAboveTheLimit() {
        // [T-dead-hangpolicy] The shouldKillLiveWork(hang, duration) arms that
        // used to live here were deleted with the dead helper: the hang/kill
        // decision is now ShellIdleSafety's, and GuestWorkloadPolicy keeps only
        // the owned-pid cap.
        assertTrue(GuestWorkloadPolicy.exceedsProcessCap(GuestWorkloadPolicy.PROCESS_LIMIT + 1))
        assertFalse(GuestWorkloadPolicy.exceedsProcessCap(GuestWorkloadPolicy.PROCESS_LIMIT))
    }

    @Test
    fun longGapStillCountsAndOnlySamplingIsReduced() {
        assertTrue(GuestWorkloadPolicy.countsHang(48_000L, 30_000L, workloadLive = false))
        assertTrue(GuestWorkloadPolicy.countsHang(48_000L, 30_000L, workloadLive = true))
        assertFalse(GuestWorkloadPolicy.shouldSampleHang(48_000L, 30_000L, workloadLive = false))
        assertTrue(GuestWorkloadPolicy.shouldSampleHang(48_000L, 30_000L, workloadLive = true))
        assertTrue(GuestWorkloadPolicy.shouldSampleHang(4_000L, 30_000L, workloadLive = false))
    }

    @Test
    fun reparentedChildrenStayInTheTree() {
        val links = listOf(
            ProcLink(pid = 7254, ppid = 6530, tracerPid = 0),
            ProcLink(pid = 8988, ppid = 6530, tracerPid = 7254),
            ProcLink(pid = 8989, ppid = 6530, tracerPid = 7254),
            ProcLink(pid = 1, ppid = 0, tracerPid = 0),
        )
        assertEquals(setOf(7254, 8988, 8989), ProcessTree.collect(7254, links))
        assertEquals(setOf(1), ProcessTree.collect(1, links))
    }

    @Test
    fun statPgrpIgnoresSpacesInsideComm() {
        assertEquals(7254, ProcessTree.parseStatPgrp("7254 (proot) S 6530 7254 7254"))
        assertEquals(42, ProcessTree.parseStatPgrp("9 (python 3) R 1 42 42"))
    }

    @Test
    fun hostSuTimeoutIsCappedAndBroadFindIsNotRefused() {
        assertEquals(120_000L, GuestWorkloadPolicy.clampHostTimeout(999_000L))
        assertEquals(15_000L, GuestWorkloadPolicy.clampHostTimeout(15_000L))
        assertTrue(GuestWorkloadPolicy.requiresFreshConfirm("su -c id"))
        // [T-dead-hangpolicy] The isBroadFind() predicate that used to be
        // asserted here was deleted (no main-code reader — a broad find is slow,
        // not irreversible). The behaviour it described still holds: the brakes
        // are the clock, the output rate and the resident window, NOT a refusal.
        assertNull(GuestWorkloadPolicy.hostRefusal("find /var/minis /tmp /root /home /data -maxdepth 3"))
        assertNull(GuestWorkloadPolicy.hostRefusal("find /var/minis/workspace -maxdepth 4"))
    }

    @Test
    fun irreversibleHostDamageIsStillRefused() {
        for (command in listOf(
            "mkfs.ext4 /dev/block/sda",
            "dd if=/dev/zero of=/dev/block/sda",
            "rm -rf /",
        )) {
            assertNotNull("$command must stay refused", GuestWorkloadPolicy.hostRefusal(command))
        }
    }

    /**
     * [T-rm-root-false-positive] The regression this whole fix exists for.
     *
     * `contains("rm -rf /")` matched every absolute path, so ordinary cleanup
     * was refused with exit 126 in every mode and the only workaround was to
     * hide the target (`cd` in, use relative paths). These are the exact shapes
     * that were denied in the field, including the app's own notification
     * CLEANUP script and a `grep` whose pattern mentioned the string.
     */
    @Test
    fun legitimateAbsolutePathsAreNotRefusedAsRootDeletion() {
        for (command in listOf(
            "rm -rf /sdcard/Download/dbcheck",
            "rm -rf /tmp/x; echo done",
            "rm -rf /sdcard/Download/dbcheck && rm -f /sdcard/Download/installed-base.apk",
            "rm -rf /tmp/* /var/tmp/* 2>/dev/null || true",
            "rm -rf /var/minis/workspace/build",
            "rm -rf /data/local/tmp/probe",
            "grep -rn 'rm -rf /' src/",
            "echo \"rm -rf /\"",
        )) {
            assertNull("must not be refused: $command", GuestWorkloadPolicy.hostRefusal(command))
        }
    }

    /**
     * `mkfs` was matched as a substring, so any command merely containing the
     * word was refused as a block-device write. It is an argv token now. The
     * `of=/dev/` and redirect arms stay substring checks — what they match is a
     * literal device path, not an ordinary word.
     */
    @Test
    fun blockDeviceMatchIsProgramLevelNotSubstring() {
        assertNull(GuestWorkloadPolicy.blockDeviceRefusal("man mkfs"))
        assertNull(GuestWorkloadPolicy.blockDeviceRefusal("man mkfs.ext4"))
        assertNull(GuestWorkloadPolicy.blockDeviceRefusal("echo 'never run mkfs.ext4 here'"))
        assertNull(GuestWorkloadPolicy.blockDeviceRefusal("cat /var/log/mkfs-history.log"))
        // Reading a device node into a file is not a block-device write.
        assertNull(GuestWorkloadPolicy.blockDeviceRefusal("cat /dev/block/mmcblk0 > /tmp/img"))
        assertNull(GuestWorkloadPolicy.blockDeviceRefusal("ls /dev/block"))
        for (command in listOf(
            "mkfs.ext4 /dev/block/sda",
            "/sbin/mkfs.f2fs /dev/block/mmcblk0",
            // riskUnits must peel wrappers, or this becomes a trivial bypass.
            "sudo mkfs.ext4 /dev/block/sda",
            "busybox mkfs.ext4 /dev/block/sda",
            "dd if=/dev/zero of=/dev/block/sda",
            "dd if=/dev/zero of=/dev/mmcblk0",
            "cat img > /dev/block/mmcblk0",
            // [T-blockdev-redirect-space] spaced, appending and fd-prefixed forms.
            "cat img >/dev/block/mmcblk0",
            "echo x >> /dev/mmcblk0p1",
            "cat y 2> /dev/block/by-name/boot",
        )) {
            assertNotNull("must stay refused: $command", GuestWorkloadPolicy.blockDeviceRefusal(command))
        }
    }

    /**
     * [T-yoyo-root-deletion] YOYO means "full-auto except fatal-confirm", and
     * the gate turns a root deletion into a mustPrompt confirmation. Refusing it
     * again in the guest executor made that approval a dead end: the user tapped
     * through and the command still died with exit 126. The guest rootfs is also
     * rebuildable, so the relaxation is recoverable.
     *
     * Block-device writes are NOT part of the relaxation in any mode — a
     * rewritten partition table bricks the device with no undo surface.
     */
    @Test
    fun yoyoRelaxesRootDeletionButNeverBlockDevice() {
        assertNull(
            "YOYO guest executor must not re-refuse a confirmed root deletion",
            GuestWorkloadPolicy.hostRefusal("rm -rf /", allowRootDeletion = true),
        )
        assertNotNull(
            "ASK must still hard-refuse a root deletion",
            GuestWorkloadPolicy.hostRefusal("rm -rf /", allowRootDeletion = false),
        )
        assertNotNull(
            "default must stay strict for callers that do not opt in",
            GuestWorkloadPolicy.hostRefusal("rm -rf /"),
        )
        for (command in listOf("mkfs.ext4 /dev/block/sda", "dd if=/dev/zero of=/dev/block/sda")) {
            assertNotNull(
                "block device stays refused even under YOYO: $command",
                GuestWorkloadPolicy.hostRefusal(command, allowRootDeletion = true),
            )
        }
        // Host `su` runs against the phone, not the sandbox: SuOffloadHandler
        // must keep the default. This asserts the two arms are separable.
        assertNotNull(GuestWorkloadPolicy.rootDeletionRefusal("rm -rf /"))
        assertNull(GuestWorkloadPolicy.rootDeletionRefusal("rm -rf /tmp/x"))
    }

    @Test
    fun boundedBufferSetsTruncatedOnlyAfterACharIsDropped() {
        val buf = BoundedOutputBuffer(headChars = 4, tailChars = 4)
        buf.append("abcdefghij")
        assertTrue(buf.truncated)
        assertEquals(2, buf.dropped)
        assertTrue(buf.toString().contains("abcd"))
        assertTrue(buf.toString().endsWith("ghij"))
        val exact = BoundedOutputBuffer(headChars = 4, tailChars = 4)
        exact.append("abcdef")
        assertFalse(exact.truncated)
        assertEquals("abcdef", exact.toString())
    }

    /**
     * [T-nproc-alignment] RLIMIT_NPROC counts per UID and includes the app's
     * own processes — the old batch/service/setup value of 4096 let a guest
     * `make -j$(nproc)` starve the host app's forks (EAGAIN in the very
     * watcher meant to clean up). The rlimit now stays under a UID-safe
     * ceiling, and the SandboxWorkload watchdog tripwire (PROCESS_LIMIT)
     * sits BELOW the build-class rlimits so the kill path fires first.
     */
    @Test
    fun nprocBudgetsStayUnderTheUidSafeCeiling() {
        val budgets = mapOf(
            "interactive" to BudgetClassifier.interactive(),
            "normal" to BudgetClassifier.normal(),
            "batch" to BudgetClassifier.batch(),
            "service" to BudgetClassifier.service(),
            "setup" to BudgetClassifier.setup(),
        )
        for ((name, b) in budgets) {
            assertTrue("$name nproc ${b.nproc} exceeds the UID-safe ceiling", b.nproc <= 1024)
        }
        assertTrue(GuestWorkloadPolicy.PROCESS_LIMIT < BudgetClassifier.batch().nproc)
        assertTrue(GuestWorkloadPolicy.PROCESS_LIMIT < BudgetClassifier.service().nproc)
        assertTrue(GuestWorkloadPolicy.PROCESS_LIMIT < BudgetClassifier.setup().nproc)
    }

    /**
     * [T-rlimit-soft-before-hard] The order IS the correctness property.
     *
     * bash's `ulimit -H -x N` calls setrlimit with {cur = the CURRENT soft,
     * max = N}; it does not lower the soft for you. Linux rejects that with
     * EINVAL whenever the inherited soft is already above N — which it always
     * is at shell boot, since the soft comes from the Android app process. So
     * emitting `-H` before `-S` meant every hard limit failed, silently,
     * behind the `2>/dev/null || true` that is there for a good reason (a
     * failed rlimit must not brick the shell).
     *
     * Observed on device before the fix: `Max processes 1024/57851`,
     * `Max file size 8GiB/unlimited`, and `ulimit -S -u 50000` succeeded from
     * an unprivileged guest process. The soft half looked enforced while the
     * ceiling was fiction.
     *
     * This asserts the emitted ORDER, not just the presence of both lines —
     * presence was already true when the bug shipped.
     */
    @Test
    fun everyRlimitLowersSoftBeforeHard() {
        // One budget with a CPU limit and one without, so the conditional
        // `-t` branch is covered on both sides.
        val budgets = mapOf(
            "normal" to BudgetClassifier.normal(),
            "service" to BudgetClassifier.service(),
        )
        for ((name, budget) in budgets) {
            // Drive the emitter with the budget DIRECTLY. GuestLimits.wrap()
            // re-classifies its argument from the command text, so wrapping a
            // fixed command here would test the classifier's guess rather than
            // the budget under iteration — the service case (cpuSeconds == 0)
            // would be handed a script built for NORMAL and the `-t` branch
            // would never be covered on the "absent" side.
            val wrapped = GuardianScript.oneshot("true", budget)
            for (flag in listOf("t", "u", "f")) {
                val soft = wrapped.indexOf("ulimit -S -$flag ")
                val hard = wrapped.indexOf("ulimit -H -$flag ")
                val present = soft >= 0 && hard >= 0
                if (flag == "t" && budget.cpuSeconds == 0) {
                    assertFalse("$name: cpuSeconds==0 must emit no -t limit", present)
                    continue
                }
                assertTrue("$name: expected both -S -$flag and -H -$flag", present)
                assertTrue(
                    "$name: -S -$flag must be emitted BEFORE -H -$flag " +
                        "(soft=$soft hard=$hard) or the hard setrlimit fails with EINVAL",
                    soft < hard,
                )
            }
        }
    }

    /**
     * The headroom multiplier must not put the hard ceiling anywhere near the
     * UID ceiling. RLIMIT_NPROC counts per UID and includes the app's own
     * processes, so a guest that could reach the kernel's value would be able
     * to starve the app's forks — including the watcher meant to clean it up.
     */
    @Test
    fun hardCeilingStaysWellUnderTheUidLimit() {
        val kernelUidNproc = 57851 // measured on the reference device
        val largestSoft = listOf(
            BudgetClassifier.interactive(),
            BudgetClassifier.normal(),
            BudgetClassifier.batch(),
            BudgetClassifier.service(),
            BudgetClassifier.setup(),
        ).maxOf { it.nproc }
        val hard = largestSoft * GuardianScript.HARD_HEADROOM
        assertTrue("hard nproc $hard is not below the soft $largestSoft", hard >= largestSoft)
        assertTrue(
            "hard nproc ceiling $hard leaves the app too little of the UID budget $kernelUidNproc",
            hard * 4 <= kernelUidNproc,
        )
    }

    /** [T-memory-poison-guard] The sandbox-side quota gate: pure decision. */
    @Test
    fun memoryQuotaRefusalBlocksWriteSyntaxOnlyOnceSpent() {
        val max = 30
        // Under quota: direct write syntax passes.
        assertNull(
            GuestWorkloadPolicy.memoryQuotaRefusal(
                "echo x >> /var/minis/memory/2026-10-02.md", max - 1, max,
            ),
        )
        // At quota: every direct write shape is refused.
        for (cmd in listOf(
            "echo x >> /var/minis/memory/2026-10-02.md",
            "echo x > /var/minis/memory/2026-10-02.md",
            "tee -a /var/minis/memory/today.md",
            "rm /var/minis/memory/today.md",
            "sed -i s/a/b/ /var/minis/memory/today.md",
            "truncate -s 0 /var/minis/memory/today.md",
        )) {
            assertNotNull("quota spent must refuse: $cmd", GuestWorkloadPolicy.memoryQuotaRefusal(cmd, max, max))
        }
        // Reads stay available so the agent can see why it was refused.
        assertNull(GuestWorkloadPolicy.memoryQuotaRefusal("cat /var/minis/memory/today.md", max, max))
        assertNull(GuestWorkloadPolicy.memoryQuotaRefusal("grep x /var/minis/memory/today.md", max, max))
        // [T-devnull-redirect] a discarded stderr is not a write: reads with
        // 2>/dev/null stay available at quota…
        assertNull(GuestWorkloadPolicy.memoryQuotaRefusal("cat /var/minis/memory/today.md 2>/dev/null", max, max))
        // …while the real write arm still fires with the same redirect.
        assertNotNull(
            GuestWorkloadPolicy.memoryQuotaRefusal("echo x >> /var/minis/memory/today.md 2>/dev/null", max, max),
        )
        // Unrelated paths are untouched.
        assertNull(GuestWorkloadPolicy.memoryQuotaRefusal("echo x >> /var/minis/workspace/a.md", max, max))
    }
}
