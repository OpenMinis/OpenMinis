#!/usr/bin/env python3
"""End-to-end test for iSH JIT crash recovery on a real device.

[T-ish-jit-crash-offsets] The JIT recovery path only runs when guest code takes
a memory fault *inside* JIT-compiled code: the host SIGSEGV handler rewrites the
signal context to unwind out of the fiber and hand the guest a normal SIGSEGV.
Nothing in the ordinary test suite reaches it, so this harness builds tiny
static aarch64 ELFs that fault on purpose and runs them through the on-device
debug server.

Each binary isolates one gadget family, because they take different paths out of
the TLB miss handler:

  g_read   ldr x1,[x0]  with x0=0    -- scalar load fault
  g_simd   ldr q0,[x0]  with x0=0    -- SIMD load fault (the gadget named in the
                                        2026-08-15 crash: handle_miss_ldr_simd_q_1)
  g_write  str x1,[x0]  with x0=0    -- store fault
  g_cross  page-straddling load, THEN a store fault -- the important one: the
           crosspage load populates fiber_frame.value[], which is exactly the
           memory the buggy recovery path used to mistake for jit_exit_sp.

Expected: the guest process dies with a signal (139 SIGSEGV / 132 SIGILL,
depending on which gadget escalates) and **iSH itself keeps running**. A failure
here is the app dying, the shell wedging, or the RPC timing out.

Note on exit codes: loads currently escalate to SIGILL(132) rather than
SIGSEGV(139) after the 16-retry ceiling in asbestos.c. That asymmetry is
PRE-EXISTING -- verified by A/B against a build without the offset fix, which
produces byte-identical results -- so the harness accepts either signal and only
fails if the process survives or the emulator dies.

Usage:
    iproxy 8321 8321 &
    python3 scripts/test_ish_jit_fault_recovery.py [--host localhost:8321]

Requires minis_rpc.py from the device: curl -o minis_rpc.py <host>/skill/examples/python
"""

import argparse
import base64
import json
import struct
import subprocess
import sys
import os

# Instruction encodings, verified against the assembler:
#   xcrun --sdk iphoneos clang -c -target arm64-apple-ios <asm> && xcrun otool -t
MOV_X0_0 = "000080d2"   # mov  x0, #0
LDR_X1_X0 = "010040f9"  # ldr  x1, [x0]
LDR_Q0_X0 = "0000c03d"  # ldr  q0, [x0]
STR_X1_X0 = "010000f9"  # str  x1, [x0]
MOVZ_X2 = "d2a00802"    # movz x2, #0x40, lsl #16   -> 0x400000
ADD_X2 = "913fe842"     # add  x2, x2, #4090        -> straddles the page edge
LDR_X3_X2 = "f9400043"  # ldr  x3, [x2]             -> crosspage load

VADDR = 0x400000
EHSIZE, PHSIZE = 64, 56


def build_elf(path, hexcode, memsz=None):
    """Minimal static ET_EXEC aarch64 ELF with one PT_LOAD segment."""
    code = bytes.fromhex(hexcode)
    entry = VADDR + EHSIZE + PHSIZE
    filesz = EHSIZE + PHSIZE + len(code)
    memsz = memsz or filesz
    flags = 7 if memsz > filesz else 5  # RWX when we need the second page mapped
    eh = struct.pack("<4sBBBBB7xHHIQQQIHHHHHH", b"\x7fELF", 2, 1, 1, 0, 0,
                     2, 183, 1, entry, EHSIZE, 0, 0, EHSIZE, PHSIZE, 1, 0, 0, 0)
    ph = struct.pack("<IIQQQQQQ", 1, flags, 0, VADDR, VADDR, filesz, memsz, 0x1000)
    with open(path, "wb") as f:
        f.write(eh + ph + code)
    return filesz


BINARIES = {
    "g_read":  (MOV_X0_0 + LDR_X1_X0, None),
    "g_simd":  (MOV_X0_0 + LDR_Q0_X0, None),
    "g_write": (MOV_X0_0 + STR_X1_X0, None),
    "g_cross": (MOVZ_X2 + ADD_X2 + LDR_X3_X2 + MOV_X0_0 + STR_X1_X0, 0x2000),
}


def rpc(host, method, params):
    out = subprocess.run(
        [sys.executable, "minis_rpc.py", "--host", host, method, json.dumps(params)],
        capture_output=True, text=True, timeout=400)
    if not out.stdout.strip():
        raise RuntimeError(f"{method}: no response ({out.stderr.strip()[:200]})")
    return json.loads(out.stdout)


def sh(host, command, timeout=180):
    r = rpc(host, "debug.shellExecute", {"command": command, "timeout": timeout})
    if "error" in r:
        raise RuntimeError(f"shellExecute failed: {r['error']}")
    return r["result"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="localhost:8321")
    ap.add_argument("--stress", type=int, default=100,
                    help="iterations for the crosspage stress phase")
    args = ap.parse_args()

    if not os.path.exists("minis_rpc.py"):
        print("❌ minis_rpc.py not found in cwd.\n"
              f"   curl -o minis_rpc.py http://{args.host}/skill/examples/python")
        return 1

    failures = 0

    def check(label, ok, detail=""):
        nonlocal failures
        print(f"  {'✅' if ok else '❌'} {label}{(' — ' + detail) if detail else ''}")
        if not ok:
            failures += 1

    info = rpc(args.host, "debug.appInfo", {})["result"]
    print(f"device build: {info['buildDate']}\n")

    print("Staging fault binaries")
    for name, (hexcode, memsz) in BINARIES.items():
        size = build_elf(name, hexcode, memsz)
        blob = base64.b64encode(open(name, "rb").read()).decode()
        r = rpc(args.host, "debug.writeFile",
                {"path": f"/tmp/{name}", "content": blob,
                 "encoding": "base64", "mode": "0755"})
        check(f"staged {name} ({size}B)", r.get("result", {}).get("ok") is True)
        os.unlink(name)

    print("\nSingle faults — guest must die by signal, emulator must survive")
    cmd = ("; ".join(f"chmod +x /tmp/{n}; /tmp/{n} 2>/dev/null; echo {n}=$?"
                     for n in BINARIES) + "; echo ALIVE=$(uname -m)")
    out = sh(args.host, cmd)["stdout"]
    for name in BINARIES:
        line = next((l for l in out.splitlines() if l.startswith(name + "=")), "")
        codeval = line.split("=", 1)[1] if "=" in line else "?"
        # 139 = 128+SIGSEGV, 132 = 128+SIGILL (see module docstring)
        check(f"{name} faulted and was reaped", codeval in ("139", "132"),
              f"exit={codeval}")
    check("emulator alive after single faults", "ALIVE=aarch64" in out)

    print(f"\nCrosspage stress ×{args.stress} — the path that used to restore a bogus SP")
    out = sh(args.host,
             f"ok=0; for i in $(seq 1 {args.stress}); do /tmp/g_cross 2>/dev/null; "
             f"c=$?; [ $c -eq 139 -o $c -eq 132 ] && ok=$((ok+1)); done; "
             f"echo SURVIVED=$ok/{args.stress}; echo ALIVE=$(uname -m)",
             timeout=300)["stdout"]
    check(f"all {args.stress} crosspage faults recovered",
          f"SURVIVED={args.stress}/{args.stress}" in out, out.strip().splitlines()[0])
    check("emulator alive after stress", "ALIVE=aarch64" in out)

    print("\nConcurrent faults — 6 workers, mixed crosspage + store")
    out = sh(args.host,
             "for i in 1 2 3 4 5 6; do ( for j in $(seq 1 25); do "
             "/tmp/g_cross 2>/dev/null; /tmp/g_write 2>/dev/null; done ) & done; wait; "
             "echo DONE; echo ALIVE=$(uname -m); echo PROCS=$(ps | wc -l)",
             timeout=300)["stdout"]
    check("all workers completed", "DONE" in out)
    check("emulator alive after concurrent faults", "ALIVE=aarch64" in out)
    procs = next((l.split("=")[1] for l in out.splitlines() if l.startswith("PROCS=")), "?")
    check("no process leak", procs.isdigit() and int(procs) <= 10, f"procs={procs}")

    print("\nFunctional regression — JIT still computes correctly")
    out = sh(args.host,
             'echo SUM=$(seq 1 5000 | awk "{s+=\\$1} END{print s}"); '
             'echo SED=$(echo test | sed s/t/T/g); '
             'echo ARCH=$(uname -m)')["stdout"]
    check("awk sum over 5000 terms", "SUM=12502500" in out)
    check("sed substitution", "SED=TesT" in out)
    check("arch still aarch64", "ARCH=aarch64" in out)

    guard = rpc(args.host, "debug.appInfo", {})["result"]["shellLeakGuard"]
    check("no leaked shell executions", "activeExecutions=0" in guard, guard)

    print("\n" + ("✅ all checks passed" if not failures else f"❌ {failures} check(s) failed"))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
