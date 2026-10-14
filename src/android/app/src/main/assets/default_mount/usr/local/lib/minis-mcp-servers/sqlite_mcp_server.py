#!/usr/bin/env python3
"""Small stdio MCP server for SQLite, with no third-party dependencies."""
import argparse
import json
import sqlite3
import sys

PROTOCOL_VERSION = "2025-06-18"


def response(req_id, result=None, error=None):
    value = {"jsonrpc": "2.0", "id": req_id}
    if error is not None:
        value["error"] = error
    else:
        value["result"] = result
    return value


def tool_specs():
    text = {"type": "object", "properties": {"query": {"type": "string"}}, "required": ["query"]}
    return [
        {"name": "read_query", "description": "执行 SELECT 查询", "inputSchema": text},
        {"name": "write_query", "description": "执行 INSERT、UPDATE、DELETE 或 CREATE 语句", "inputSchema": text},
        {"name": "list_tables", "description": "列出 SQLite 数据库中的表", "inputSchema": {"type": "object", "properties": {}}},
        {"name": "describe_table", "description": "查看指定数据表结构", "inputSchema": {"type": "object", "properties": {"table_name": {"type": "string"}}, "required": ["table_name"]}},
    ]


def call_tool(db_path, name, args):
    with sqlite3.connect(db_path) as db:
        db.row_factory = sqlite3.Row
        if name == "read_query":
            query = str(args.get("query", "")).strip()
            if not query.lower().startswith(("select", "with", "pragma", "explain")):
                raise ValueError("read_query 只允许 SELECT/WITH/PRAGMA/EXPLAIN")
            rows = [dict(row) for row in db.execute(query)]
            return json.dumps(rows, ensure_ascii=False)
        if name == "write_query":
            query = str(args.get("query", "")).strip()
            if not query or query.lower().startswith(("select", "pragma")):
                raise ValueError("write_query 需要写入或 DDL 语句")
            cursor = db.execute(query)
            db.commit()
            return json.dumps({"affected_rows": cursor.rowcount}, ensure_ascii=False)
        if name == "list_tables":
            rows = db.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").fetchall()
            return json.dumps([row[0] for row in rows], ensure_ascii=False)
        if name == "describe_table":
            table = str(args.get("table_name", "")).strip()
            if not table or any(ch not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_" for ch in table):
                raise ValueError("table_name 无效")
            rows = db.execute("PRAGMA table_info(\"" + table + "\")").fetchall()
            return json.dumps([dict(row) for row in rows], ensure_ascii=False)
        raise ValueError("未知工具: " + name)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--db-path", required=True)
    args = parser.parse_args()
    for line in sys.stdin:
        try:
            req = json.loads(line)
            method = req.get("method")
            req_id = req.get("id")
            if method == "initialize":
                result = {"protocolVersion": PROTOCOL_VERSION, "capabilities": {"tools": {}}, "serverInfo": {"name": "minis-sqlite", "version": "1.0.0"}}
                out = response(req_id, result)
            elif method == "notifications/initialized":
                continue
            elif method == "tools/list":
                out = response(req_id, {"tools": tool_specs()})
            elif method == "tools/call":
                params = req.get("params") or {}
                try:
                    text = call_tool(args.db_path, str(params.get("name", "")), params.get("arguments") or {})
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
