#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# ==============================================================================
# Minis Ultra - 内置 APK 逆向 MCP 服务端 (stdio transport)
# ------------------------------------------------------------------------------
# 自包含轻量 MCP 服务，包装沙箱内 android-suite 已装配的逆向工具链：
#   apktool  (解包/回编译)   jadx (dex->java)   aapt (清单解码)
#   zipalign + apksigner (重打包签名)           keytool (调试密钥库)
#
# 协议：MCP stdio，逐行 JSON-RPC（与 McpManager.discoverStdioTools /
# executeStdioTool 的 line-delimited JSON 交互方式对齐）。
# 依赖：python3 + apktool/jadx（由「Android & 移动全栈开发套件 ->
#       Android 逆向分析与代码审计」子组件安装）。
# ==============================================================================
import json
import os
import shutil
import subprocess
import sys
import tempfile

MAX_OUTPUT_CHARS = 60000  # 单次工具输出上限，防止海量日志撑爆上下文


def run(cmd, timeout=600):
    """执行沙箱命令，返回 (ok, output)。"""
    try:
        proc = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
        out = (proc.stdout or "") + (proc.stderr or "")
        return proc.returncode == 0, truncate(out.strip())
    except subprocess.TimeoutExpired:
        return False, "命令执行超时 (>{:d}s): {}".format(timeout, " ".join(cmd))
    except FileNotFoundError as e:
        return False, "缺少工具：{}，请先在【插件与工具中心】装配「Android & 移动全栈开发套件」的逆向子组件".format(e)
    except Exception as e:  # noqa: BLE001
        return False, "执行异常: {}".format(e)


def truncate(text):
    if len(text) <= MAX_OUTPUT_CHARS:
        return text
    return text[:MAX_OUTPUT_CHARS] + "\n...[输出过长已截断]..."


def require_tool(name):
    if shutil.which(name) is None:
        raise RuntimeError(
            "缺少工具 '{}'：请先在【插件与工具中心】装配「Android & 移动全栈开发套件」（含逆向分析与代码审计子组件）".format(name)
        )


# ============================== 工具实现 ==============================

def tool_decode_apk(args):
    """apktool d：完整解包 APK（资源 + Smali），输出可回编译的工程目录。"""
    apk_path = (args.get("apk_path") or "").strip()
    if not apk_path:
        return False, "缺少参数 apk_path"
    output_dir = (args.get("output_dir") or "").strip() or apk_path + ".out"
    require_tool("apktool")
    return run(["apktool", "d", "-f", apk_path, "-o", output_dir])


def tool_build_apk(args):
    """apktool b：将已修改的 Smali/资源目录回编译为 APK。"""
    project_dir = (args.get("project_dir") or "").strip()
    if not project_dir:
        return False, "缺少参数 project_dir"
    output_apk = (args.get("output_apk") or "").strip()
    require_tool("apktool")
    cmd = ["apktool", "b", project_dir]
    if output_apk:
        cmd += ["-o", output_apk]
    return run(cmd)


def tool_analyze_manifest(args):
    """深度审计 AndroidManifest：优先 aapt dump badging/xmltree，回退 apktool 解码。"""
    path = (args.get("apk_or_manifest_path") or "").strip()
    if not path:
        return False, "缺少参数 apk_or_manifest_path"
    if path.endswith(".apk"):
        if shutil.which("aapt"):
            ok, out = run(["aapt", "dump", "badging", path])
            if ok:
                return True, out
        require_tool("apktool")
        with tempfile.TemporaryDirectory() as tmp:
            ok, out = run(["apktool", "d", "-f", "-s", "-o", tmp, path])
            if not ok:
                return False, out
            manifest = os.path.join(tmp, "AndroidManifest.xml")
            if os.path.isfile(manifest):
                with open(manifest, encoding="utf-8", errors="replace") as f:
                    return True, truncate(f.read())
            return False, "解码后未找到 AndroidManifest.xml"
    if os.path.isfile(path):
        with open(path, encoding="utf-8", errors="replace") as f:
            return True, truncate(f.read())
    return False, "目标不存在: {}".format(path)


def tool_extract_strings(args):
    """提取 APK 字符串与敏感特征（硬编码 URL / API Key / 加解密关键字）。"""
    path = (args.get("apk_or_res_path") or "").strip()
    if not path:
        return False, "缺少参数 apk_or_res_path"
    require_tool("apktool")
    patterns = [
        r"https?://[^\s\"'<>]+",
        r"(?i)(api[_-]?key|apikey|secret|token|password|passwd|credential)\s*[:=]\s*[^\s\"',;]+",
        r"(?i)(AES|DES|RSA|MD5|SHA1|SHA256|Cipher\.getInstance)",
    ]
    with tempfile.TemporaryDirectory() as tmp:
        ok, out = run(["apktool", "d", "-f", "-s", "-o", tmp, path])
        if not ok:
            return False, out
        parts = []
        for pat in patterns:
            ok, out = run(
                ["grep", "-rhoE", pat, tmp, "--include=*.xml", "--include=*.properties",
                 "--include=*.json", "--include=*.txt", "--include=*.smali"],
                timeout=300,
            )
            if ok and out:
                lines = sorted(set(out.splitlines()))
                parts.append("### {}\n{}".format(pat, "\n".join(lines[:200])))
        body = "\n\n".join(parts) if parts else "未在解码产物中发现明显的敏感字符串"
        return True, truncate(body)


def tool_search_smali(args):
    """在解包工程（Smali/资源）中检索关键特征。"""
    project_dir = (args.get("project_dir") or "").strip()
    pattern = (args.get("pattern") or "").strip()
    if not project_dir or not pattern:
        return False, "缺少参数 project_dir / pattern"
    if not os.path.isdir(project_dir):
        return False, "解包工程目录不存在: {}".format(project_dir)
    return run(
        ["grep", "-rnE", "--color=never", pattern, project_dir],
        timeout=300,
    )


def tool_sign_apk(args):
    """zipalign 对齐 + apksigner 使用内置调试密钥库签名 APK。"""
    apk_path = (args.get("apk_path") or "").strip()
    if not apk_path:
        return False, "缺少参数 apk_path"
    output_apk = (args.get("output_apk") or "").strip() or apk_path + ".signed.apk"
    require_tool("zipalign")
    require_tool("apksigner")
    require_tool("keytool")

    keystore = os.path.expanduser("~/.minis-debug.keystore")
    if not os.path.isfile(keystore):
        ok, out = run([
            "keytool", "-genkeypair", "-v",
            "-keystore", keystore,
            "-alias", "minis",
            "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "10000",
            "-storepass", "minis123", "-keypass", "minis123",
            "-dname", "CN=Minis Debug, OU=Minis, O=Minis, C=CN",
        ])
        if not ok:
            return False, "生成调试密钥库失败: {}".format(out)

    tmp_dir = tempfile.mkdtemp(prefix="minis-sign-")
    try:
        aligned = os.path.join(tmp_dir, "aligned.apk")
        ok, out = run(["zipalign", "-f", "4", apk_path, aligned])
        if not ok:
            return False, out
        ok, out = run([
            "apksigner", "sign",
            "--ks", keystore,
            "--ks-pass", "pass:minis123",
            "--key-pass", "pass:minis123",
            "--out", output_apk,
            aligned,
        ])
        if not ok:
            return False, out
        return True, "签名完成: {}\n(调试密钥库: {})".format(output_apk, keystore)
    finally:
        shutil.rmtree(tmp_dir, ignore_errors=True)


TOOLS = {
    "decode_apk": {
        "description": "自动化反编译 APK 文件，输出 Smali 源码与解包资源目录",
        "inputSchema": {
            "type": "object",
            "properties": {
                "apk_path": {"type": "string", "description": "APK 文件的绝对路径"},
                "output_dir": {"type": "string", "description": "解包输出目录（可选，默认 <apk>.out）"},
            },
            "required": ["apk_path"],
        },
        "handler": tool_decode_apk,
    },
    "build_apk": {
        "description": "将已修改的 Smali 与资源目录重新打包编译为 APK 文件",
        "inputSchema": {
            "type": "object",
            "properties": {
                "project_dir": {"type": "string", "description": "解包工程目录绝对路径"},
                "output_apk": {"type": "string", "description": "生成的 APK 目标路径（可选）"},
            },
            "required": ["project_dir"],
        },
        "handler": tool_build_apk,
    },
    "analyze_manifest": {
        "description": "深度审计 AndroidManifest.xml 清单文件，提取四大组件导出状态与高危权限",
        "inputSchema": {
            "type": "object",
            "properties": {
                "apk_or_manifest_path": {"type": "string", "description": "APK 文件路径或 AndroidManifest.xml 路径"},
            },
            "required": ["apk_or_manifest_path"],
        },
        "handler": tool_analyze_manifest,
    },
    "extract_strings": {
        "description": "从 APK 资源中提取全部字符串、硬编码 API Key、URL 与潜在敏感凭据",
        "inputSchema": {
            "type": "object",
            "properties": {
                "apk_or_res_path": {"type": "string", "description": "APK 路径或 res 资源目录路径"},
            },
            "required": ["apk_or_res_path"],
        },
        "handler": tool_extract_strings,
    },
    "search_smali": {
        "description": "在 Smali 代码中全局检索关键方法、签名校验逻辑或加密解密特征",
        "inputSchema": {
            "type": "object",
            "properties": {
                "project_dir": {"type": "string", "description": "解包工程目录绝对路径"},
                "pattern": {"type": "string", "description": "搜索关键词或正则模式"},
            },
            "required": ["project_dir", "pattern"],
        },
        "handler": tool_search_smali,
    },
    "sign_apk": {
        "description": "对生成的 APK 执行 zipalign 内存对齐与 V2/V3 签名（内置调试密钥库）",
        "inputSchema": {
            "type": "object",
            "properties": {
                "apk_path": {"type": "string", "description": "待签名的 APK 路径"},
                "output_apk": {"type": "string", "description": "签名后的 APK 输出路径（可选）"},
            },
            "required": ["apk_path"],
        },
        "handler": tool_sign_apk,
    },
}


# ============================== stdio 主循环 ==============================

def rpc_response(req_id, result):
    return {"jsonrpc": "2.0", "id": req_id, "result": result}


def handle_line(line):
    """处理单行 JSON-RPC 请求，返回响应 dict（或 None 表示忽略）。"""
    line = line.strip()
    if not line:
        return None
    try:
        req = json.loads(line)
    except json.JSONDecodeError:
        return None
    method = req.get("method")
    req_id = req.get("id")
    if method == "initialize":
        return rpc_response(req_id, {
            "protocolVersion": "2025-06-18",
            "capabilities": {"tools": {}},
            "serverInfo": {"name": "minis-apktool-mcp", "version": "1.0.0"},
        })
    if method == "tools/list":
        return rpc_response(req_id, {
            "tools": [
                {
                    "name": name,
                    "description": spec["description"],
                    "inputSchema": spec["inputSchema"],
                }
                for name, spec in TOOLS.items()
            ]
        })
    if method == "tools/call":
        params = req.get("params") or {}
        name = (params.get("name") or "").strip()
        args = params.get("arguments") or {}
        spec = TOOLS.get(name)
        if spec is None:
            return rpc_response(req_id, {
                "content": [{"type": "text", "text": "未知工具: {}".format(name)}],
                "isError": True,
            })
        try:
            ok, text = spec["handler"](args)
        except Exception as e:  # noqa: BLE001
            ok, text = False, "工具执行异常: {}".format(e)
        return rpc_response(req_id, {
            "content": [{"type": "text", "text": text}],
            "isError": not ok,
        })
    return None


def main():
    # 逐行读取 stdin，逐行输出 JSON-RPC 响应（每行 flush，保证实时性）
    for line in sys.stdin:
        resp = handle_line(line)
        if resp is not None:
            sys.stdout.write(json.dumps(resp, ensure_ascii=False) + "\n")
            sys.stdout.flush()


if __name__ == "__main__":
    main()
