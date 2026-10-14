#!/usr/bin/env python3
"""Dependency-free Git MCP server for the Minis Ultra sandbox."""
import argparse
import json
import os
import subprocess
import sys

PROTOCOL_VERSION = "2025-06-18"


def response(req_id, result=None, error=None):
    value = {"jsonrpc": "2.0", "id": req_id}
    value["error" if error is not None else "result"] = error if error is not None else result
    return value


def is_git_repo(path):
    return os.path.isdir(os.path.join(path, ".git")) or os.path.isfile(os.path.join(path, ".git"))


def resolve_repository(path):
    """Resolve a usable git work tree from --repository.

    Prefer the given path when it already is a repo; otherwise walk parents,
    then scan one level of children under /var/minis/workspace for the common
    'workspace/<project>' layout.
    """
    start = os.path.abspath(path or "/var/minis/workspace")
    if is_git_repo(start):
        return start
    current = start
    while True:
        parent = os.path.dirname(current)
        if parent == current:
            break
        if is_git_repo(parent):
            return parent
        current = parent
    if os.path.isdir(start):
        try:
            for name in sorted(os.listdir(start)):
                child = os.path.join(start, name)
                if os.path.isdir(child) and is_git_repo(child):
                    return child
        except OSError:
            pass
    return start


def run(repo, command):
    proc = subprocess.run(["git", "-C", repo] + command, capture_output=True, text=True, timeout=30)
    output = (proc.stdout or "") + (proc.stderr or "")
    if proc.returncode != 0:
        raise RuntimeError(output.strip() or "git 命令失败")
    return output.strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", default="/var/minis/workspace")
    args = parser.parse_args()
    repository = resolve_repository(args.repository)
    tools = [
        {"name": "git_status", "description": "获取工作区状态", "inputSchema": {"type": "object", "properties": {}}},
        {"name": "git_log", "description": "获取提交历史", "inputSchema": {"type": "object", "properties": {"limit": {"type": "integer"}}}},
        {"name": "git_diff", "description": "获取工作区差异", "inputSchema": {"type": "object", "properties": {"target": {"type": "string"}}}},
    ]
    for line in sys.stdin:
        try:
            req = json.loads(line)
            method, req_id = req.get("method"), req.get("id")
            if method == "initialize":
                out = response(req_id, {"protocolVersion": PROTOCOL_VERSION, "capabilities": {"tools": {}}, "serverInfo": {"name": "minis-git", "version": "1.0.0"}})
            elif method == "notifications/initialized":
                continue
            elif method == "tools/list":
                out = response(req_id, {"tools": tools})
            elif method == "tools/call":
                params = req.get("params") or {}
                try:
                    name, tool_args = params.get("name"), params.get("arguments") or {}
                    if name == "git_status": text = run(repository, ["status", "--short", "--branch"])
                    elif name == "git_log": text = run(repository, ["log", "--oneline", "-n", str(max(1, min(int(tool_args.get("limit", 20)), 200)))])
                    elif name == "git_diff": text = run(repository, ["diff", str(tool_args.get("target", ""))] if tool_args.get("target") else ["diff"])
                    else: raise ValueError("未知工具: " + str(name))
                    result = {"content": [{"type": "text", "text": text}], "isError": False}
                except Exception as exc:
                    result = {"content": [{"type": "text", "text": str(exc)}], "isError": True}
                out = response(req_id, result)
            else:
                continue
            sys.stdout.write(json.dumps(out, ensure_ascii=False) + "\n")
            sys.stdout.flush()
        except Exception as exc:
            sys.stdout.write(json.dumps(response(None, error={"code": -32603, "message": str(exc)}), ensure_ascii=False) + "\n")
            sys.stdout.flush()


if __name__ == "__main__":
    main()
