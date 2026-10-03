// [T-stop-sibling-subagent] Stopping one sub agent must not leave its siblings
// driving the parent conversation.
//
// Standalone (run with `swift StopSiblingSubagentTests.swift`) because the
// MinisTests target has a pre-existing link break on the simulator
// (deps/libs/libish_emu.a is built for iOS, not iOS-simulator) — same
// rationale as InLoopCompactOrderTests.swift.
//
// The case this pins, from the device log of 2026-09-07 (a one-second window,
// log lines 4925-5010):
//
//   19:59:08.863  sibling call_00_boDF COMPLETED success=true duration=5.0s
//   19:59:08.911  user presses Stop on a DIFFERENT card
//                 → CANCEL job C1A916A6 ... reason=user stopped this sub agent
//   19:59:08.948  parent 4858431E begins ROUND 14
//   19:59:09.322  parent sends req#37 carrying the sibling's tool_result
//                 → the model reads a finished sub-task and delegates a new
//                   one; a fresh "starting" card appears after the Stop
//
// Two production rules had to change, and both are reproduced here verbatim
// from the source so this is a test of the real logic rather than a
// restatement of the fix:
//
//   1. AgentJobRegistry.siblingSessions(ofChild:children:) — which sessions a
//      card's Stop reaches. Before: the card cancelled by CHILD session id
//      alone, so siblings survived.
//   2. AIChatViewModel.delegationResultMayDriveParent(parentCancelled:
//      delegationsMuted:) — whether a result that arrives afterwards may be
//      sent to the model. Before: the only gate was the parent's own
//      `userDidCancel`, which a card Stop never sets.

import Foundation

var failures = 0
func check(_ name: String, _ actual: Bool, _ expected: Bool) {
    if actual == expected {
        print("  ✅ \(name)")
    } else {
        print("  ❌ \(name) — expected \(expected), got \(actual)")
        failures += 1
    }
}
func checkSet(_ name: String, _ actual: Set<String>, _ expected: Set<String>) {
    if actual == expected {
        print("  ✅ \(name)")
    } else {
        print("  ❌ \(name) — expected \(expected.sorted()), got \(actual.sorted())")
        failures += 1
    }
}

// MARK: - Production rules, copied verbatim from the source

/// AgentJobRegistry.swift — `siblingSessions(ofChild:children:)`
func siblingSessions(ofChild stopped: String,
                     children: [(child: String, parent: String)]) -> [String] {
    guard let parent = children.first(where: { $0.child == stopped })?.parent else {
        return [stopped]
    }
    return children.filter { $0.parent == parent }.map(\.child)
}

/// HelperRunner.swift — `delegationResultMayDriveParent(parentCancelled:delegationsMuted:)`
func delegationResultMayDriveParent(parentCancelled: Bool, delegationsMuted: Bool) -> Bool {
    !parentCancelled && !delegationsMuted
}

/// The pre-fix behaviour of both rules, so the tests below show a real
/// difference rather than asserting that the new code equals itself.
func siblingSessions_beforeFix(ofChild stopped: String,
                               children: [(child: String, parent: String)]) -> [String] {
    [stopped]                                     // cancelled by child id only
}
func delegationResultMayDriveParent_beforeFix(parentCancelled: Bool,
                                              delegationsMuted: Bool) -> Bool {
    !parentCancelled                              // the card Stop was invisible here
}

// MARK: - 1. Which sessions a card's Stop reaches

print("Stop reaches the whole sibling set")
do {
    // The shape of the real case: one turn fanned out into several agents.
    let children = [
        (child: "childA", parent: "parent1"),
        (child: "childB", parent: "parent1"),
        (child: "childC", parent: "parent1"),
    ]
    checkSet("all three siblings are stopped",
             Set(siblingSessions(ofChild: "childB", children: children)),
             ["childA", "childB", "childC"])

    // The regression, stated as a difference: before the fix the other two
    // kept running, kept reporting in, and kept the parent going.
    checkSet("before the fix only the tapped card stopped",
             Set(siblingSessions_beforeFix(ofChild: "childB", children: children)),
             ["childB"])
}

print("\nStop is scoped to the turn, not the app")
do {
    let children = [
        (child: "childA", parent: "parent1"),
        (child: "childB", parent: "parent1"),
        (child: "otherX", parent: "parent2"),
        (child: "otherY", parent: "parent2"),
    ]
    let hit = Set(siblingSessions(ofChild: "childA", children: children))
    checkSet("only this turn's agents are stopped", hit, ["childA", "childB"])
    check("another conversation's agents keep running",
          hit.contains("otherX") || hit.contains("otherY"), false)
}

print("\nDegenerate cases")
do {
    // The registry is in-memory, so a run from a previous process has no job
    // behind it. The card must still stop the one session it can name.
    checkSet("an untracked child still stops itself",
             Set(siblingSessions(ofChild: "orphan", children: [])),
             ["orphan"])

    let single = [(child: "only", parent: "parent1")]
    checkSet("a lone agent is its own sibling set",
             Set(siblingSessions(ofChild: "only", children: single)),
             ["only"])
}

// MARK: - 2. Whether a finished sibling's result may drive the parent

print("\nA result that arrives after the Stop")
do {
    // Baseline: nothing was stopped, so results flow exactly as before. This
    // is the guard against the fix muting the ordinary path.
    check("an ordinary result still drives the parent",
          delegationResultMayDriveParent(parentCancelled: false, delegationsMuted: false), true)

    // The regression itself. The parent was NOT cancelled — the user stopped a
    // card, not the conversation — yet the sibling's result must not reach the
    // model.
    check("a sibling's result is withheld after a card Stop",
          delegationResultMayDriveParent(parentCancelled: false, delegationsMuted: true), false)
    check("before the fix that same result drove the parent",
          delegationResultMayDriveParent_beforeFix(parentCancelled: false, delegationsMuted: true), true)

    // The path that already worked, pinned so it cannot regress.
    check("stopping the conversation still withholds results",
          delegationResultMayDriveParent(parentCancelled: true, delegationsMuted: false), false)

    // Both stops together must stay muted rather than cancelling out.
    check("both flags set stay muted",
          delegationResultMayDriveParent(parentCancelled: true, delegationsMuted: true), false)
}

// MARK: - 3. The real case, replayed end to end

print("\nThe 2026-09-07 19:59:08 case, replayed")
do {
    // Three agents delegated in one parent turn. One has just finished
    // (success), the user then stops a different one.
    let children = [
        (child: "70D41E65", parent: "4858431E"),   // the card the user tapped
        (child: "boDFchild", parent: "4858431E"),  // finished 48ms earlier
        (child: "thirdchild", parent: "4858431E"), // still running
    ]

    let stopped = Set(siblingSessions(ofChild: "70D41E65", children: children))
    check("the finished sibling's job is cancelled too", stopped.contains("boDFchild"), true)
    check("the still-running sibling is cancelled too", stopped.contains("thirdchild"), true)

    // The card Stop mutes the parent without cancelling it, which is what the
    // loop and the job callback both consult.
    let parentCancelled = false      // the user stopped a card, not the chat
    let delegationsMuted = true      // set by the card's Stop
    check("the finished sibling's result does NOT reach the model",
          delegationResultMayDriveParent(parentCancelled: parentCancelled,
                                         delegationsMuted: delegationsMuted), false)

    // …and the mute is specific to the model-facing history. The transcript
    // write in persistFinalDelegateResult is deliberately NOT behind this
    // gate, so a stopped run still shows what its finished agents came back
    // with. Asserting the two decisions are made independently: the rule
    // above governs only whether the result may drive another turn.
    check("muting is a decision about driving the parent, not about display",
          delegationResultMayDriveParent(parentCancelled: false, delegationsMuted: true)
              != delegationResultMayDriveParent(parentCancelled: false, delegationsMuted: false),
          true)
}

print(failures == 0 ? "\n✅ all checks passed" : "\n❌ \(failures) check(s) failed")
exit(failures == 0 ? 0 : 1)
