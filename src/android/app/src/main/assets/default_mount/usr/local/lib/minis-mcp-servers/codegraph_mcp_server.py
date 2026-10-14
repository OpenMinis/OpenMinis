#!/usr/bin/env python3
"""
Dependency-free CodeGraph MCP Server for the Minis Ultra sandbox.

Indexes workspace symbols, call hierarchies, inheritance, and dependencies into SQLite.
Exposes MCP tools for single-step structural code intelligence (codegraph_explore,
codegraph_search, codegraph_callers, codegraph_callees, codegraph_impact, codegraph_sync).

Supported Languages:
- Python (via ast parser)
- Kotlin / Java (structural regex + signature parser)
- C / C++ (struct, class, function, include parser)
- JavaScript / TypeScript (export, class, function, import parser)
- Smali (Android bytecode reverse engineering parser)
"""

import argparse
import ast
import json
import os
import re
import sqlite3
import sys
import threading
import time
from pathlib import Path

# Force UTF-8 for stdin/stdout across all platforms
if hasattr(sys.stdin, "reconfigure"):
    sys.stdin.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

PROTOCOL_VERSION = "2025-06-18"
MAX_EXPLORE_LINES = 120
MAX_SNIPPET_LINES = 35
# 超过该大小的源文件直接跳过索引（minified JS / 生成产物 / 数据转储）
MAX_INDEX_FILE_BYTES = 1_000_000
# 触达图谱 DB 的工具在后台初始索引期间的最长等待秒数（超过则返回非失败进度提示）
GUARDED_WAIT_SECONDS = 120.0
STDOUT_WRITE_LOCK = threading.Lock()

# ==============================================================================
# Database & Graph Store
# ==============================================================================

class CodeGraphDB:
    def __init__(self, db_path=":memory:"):
        self.db_path = db_path
        # 后台线程初始索引与主线程请求共用连接；访问由 CodeIndexer 的锁串行化
        self.conn = sqlite3.connect(db_path, check_same_thread=False)
        self.conn.row_factory = sqlite3.Row
        self._init_schema()

    def _init_schema(self):
        cur = self.conn.cursor()
        cur.executescript("""
            CREATE TABLE IF NOT EXISTS files (
                file_path TEXT PRIMARY KEY,
                language TEXT,
                mtime REAL,
                symbol_count INTEGER DEFAULT 0
            );

            CREATE TABLE IF NOT EXISTS symbols (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                kind TEXT NOT NULL,
                file_path TEXT NOT NULL,
                line_start INTEGER NOT NULL,
                line_end INTEGER NOT NULL,
                signature TEXT,
                docstring TEXT,
                parent_symbol TEXT,
                FOREIGN KEY (file_path) REFERENCES files(file_path) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS refs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                caller_symbol_name TEXT,
                callee_name TEXT NOT NULL,
                file_path TEXT NOT NULL,
                line_no INTEGER NOT NULL,
                ref_kind TEXT NOT NULL
            );

            CREATE INDEX IF NOT EXISTS idx_symbols_name ON symbols(name);
            CREATE INDEX IF NOT EXISTS idx_symbols_file ON symbols(file_path);
            CREATE INDEX IF NOT EXISTS idx_refs_caller ON refs(caller_symbol_name);
            CREATE INDEX IF NOT EXISTS idx_refs_callee ON refs(callee_name);
        """)
        self.conn.commit()

    def clear_file(self, file_path):
        cur = self.conn.cursor()
        cur.execute("DELETE FROM refs WHERE file_path = ?", (file_path,))
        cur.execute("DELETE FROM symbols WHERE file_path = ?", (file_path,))
        cur.execute("DELETE FROM files WHERE file_path = ?", (file_path,))
        self.conn.commit()

    def insert_file(self, file_path, language, mtime, symbol_count):
        cur = self.conn.cursor()
        cur.execute(
            "INSERT OR REPLACE INTO files (file_path, language, mtime, symbol_count) VALUES (?, ?, ?, ?)",
            (file_path, language, mtime, symbol_count),
        )

    def insert_symbols(self, symbols_data):
        if not symbols_data:
            return
        cur = self.conn.cursor()
        cur.executemany(
            """INSERT INTO symbols (name, kind, file_path, line_start, line_end, signature, docstring, parent_symbol)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            symbols_data,
        )

    def insert_refs(self, refs_data):
        if not refs_data:
            return
        cur = self.conn.cursor()
        cur.executemany(
            """INSERT INTO refs (caller_symbol_name, callee_name, file_path, line_no, ref_kind)
               VALUES (?, ?, ?, ?, ?)""",
            refs_data,
        )

    def commit(self):
        self.conn.commit()

    def search_symbols(self, query, kind=None, limit=30):
        cur = self.conn.cursor()
        like_query = f"%{query}%"
        if kind:
            cur.execute(
                "SELECT * FROM symbols WHERE (name LIKE ? OR signature LIKE ?) AND kind = ? LIMIT ?",
                (like_query, like_query, kind, limit),
            )
        else:
            cur.execute(
                "SELECT * FROM symbols WHERE name LIKE ? OR signature LIKE ? ORDER BY CASE WHEN name = ? THEN 1 WHEN name LIKE ? THEN 2 ELSE 3 END LIMIT ?",
                (like_query, like_query, query, f"{query}%", limit),
            )
        return [dict(r) for r in cur.fetchall()]

    def find_symbol_exact(self, name):
        cur = self.conn.cursor()
        cur.execute("SELECT * FROM symbols WHERE name = ? ORDER BY id ASC", (name,))
        return [dict(r) for r in cur.fetchall()]

    def get_callers(self, callee_name, limit=30):
        cur = self.conn.cursor()
        cur.execute(
            """SELECT r.caller_symbol_name as caller, r.file_path, r.line_no, r.ref_kind, s.kind, s.signature
               FROM refs r
               LEFT JOIN symbols s ON r.caller_symbol_name = s.name AND r.file_path = s.file_path
               WHERE r.callee_name = ?
               LIMIT ?""",
            (callee_name, limit),
        )
        return [dict(r) for r in cur.fetchall()]

    def get_callees(self, caller_name, limit=30):
        cur = self.conn.cursor()
        cur.execute(
            """SELECT r.callee_name as callee, r.file_path, r.line_no, r.ref_kind, s.kind, s.signature, s.file_path as def_file, s.line_start
               FROM refs r
               LEFT JOIN symbols s ON r.callee_name = s.name
               WHERE r.caller_symbol_name = ?
               LIMIT ?""",
            (caller_name, limit),
        )
        return [dict(r) for r in cur.fetchall()]

    def get_impact(self, target_name, max_depth=2):
        visited = set()
        queue = [(target_name, 0)]
        impacted_symbols = []

        cur = self.conn.cursor()
        while queue:
            current, depth = queue.pop(0)
            if current in visited or depth >= max_depth:
                continue
            visited.add(current)

            cur.execute(
                "SELECT DISTINCT caller_symbol_name, file_path, line_no FROM refs WHERE callee_name = ?",
                (current,),
            )
            rows = cur.fetchall()
            for r in rows:
                caller = r["caller_symbol_name"]
                if caller and caller not in visited:
                    impacted_symbols.append({
                        "caller": caller,
                        "file_path": r["file_path"],
                        "line_no": r["line_no"],
                        "depth": depth + 1,
                        "cause": f"calls '{current}'",
                    })
                    queue.append((caller, depth + 1))

        return impacted_symbols

    def get_stats(self):
        cur = self.conn.cursor()
        cur.execute("SELECT count(*) FROM files")
        file_count = cur.fetchone()[0]
        cur.execute("SELECT count(*) FROM symbols")
        sym_count = cur.fetchone()[0]
        cur.execute("SELECT count(*) FROM refs")
        ref_count = cur.fetchone()[0]
        return {"files": file_count, "symbols": sym_count, "references": ref_count}


# ==============================================================================
# Language Parsers (AST & Structural Extractors)
# ==============================================================================

class LanguageParser:
    @staticmethod
    def parse_python(file_path, content):
        symbols = []
        refs = []
        try:
            tree = ast.parse(content, filename=file_path)
        except Exception:
            return LanguageParser.parse_generic_regex(file_path, content, "python")

        current_class = None

        def extract_doc(node):
            return ast.get_docstring(node) or ""

        for node in ast.walk(tree):
            if isinstance(node, ast.ClassDef):
                line_end = getattr(node, "end_lineno", node.lineno)
                doc = extract_doc(node)
                bases = [ast.unparse(b) for b in node.bases] if hasattr(ast, "unparse") else []
                sig = f"class {node.name}" + (f"({', '.join(bases)})" if bases else "")
                symbols.append((node.name, "class", file_path, node.lineno, line_end, sig, doc, None))

            elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                line_end = getattr(node, "end_lineno", node.lineno)
                doc = extract_doc(node)
                is_async = isinstance(node, ast.AsyncFunctionDef)
                args_list = [a.arg for a in node.args.args]
                sig = f"{'async ' if is_async else ''}def {node.name}({', '.join(args_list)})"
                symbols.append((node.name, "function", file_path, node.lineno, line_end, sig, doc, current_class))

                # Track function calls inside this function
                for sub in ast.walk(node):
                    if isinstance(sub, ast.Call):
                        callee_name = None
                        if isinstance(sub.func, ast.Name):
                            callee_name = sub.func.id
                        elif isinstance(sub.func, ast.Attribute):
                            callee_name = sub.func.attr
                        if callee_name:
                            refs.append((node.name, callee_name, file_path, getattr(sub, "lineno", node.lineno), "call"))

            elif isinstance(node, ast.Import):
                for alias in node.names:
                    refs.append((None, alias.name, file_path, node.lineno, "import"))
            elif isinstance(node, ast.ImportFrom):
                mod = node.module or ""
                for alias in node.names:
                    name = f"{mod}.{alias.name}" if mod else alias.name
                    refs.append((None, name, file_path, node.lineno, "import"))

        return symbols, refs

    @staticmethod
    def parse_kotlin_java(file_path, content):
        symbols = []
        refs = []
        lines = content.splitlines()

        class_regex = re.compile(r'^\s*(?:@\w+(?:\([^)]*\))?\s+)*(?:public|private|protected|internal|open|abstract|final|data|sealed)?\s*(?:class|interface|object|enum\s+class)\s+(\w+)(?:<[^>]+>)?(?:\s*:\s*([^{]+)|\s+extends\s+([^{]+)|\s+implements\s+([^{]+))?')
        func_regex = re.compile(r'^\s*(?:@\w+(?:\([^)]*\))?\s+)*(?:public|private|protected|internal|open|override|abstract|suspend|inline|fun)\s+(?:fun\s+|<[^>]+>\s+)?(\w+)\s*\(([^)]*)\)')
        import_regex = re.compile(r'^\s*import\s+(?:static\s+)?([\w.]+)')
        call_regex = re.compile(r'(\w+)\s*\(')

        current_container = None
        container_stack = []

        for idx, line in enumerate(lines):
            line_no = idx + 1
            trimmed = line.strip()

            # Imports
            imp_m = import_regex.match(line)
            if imp_m:
                refs.append((None, imp_m.group(1), file_path, line_no, "import"))
                continue

            # Class / Interface / Object
            cls_m = class_regex.match(line)
            if cls_m:
                name = cls_m.group(1)
                sig = trimmed.rstrip(" {")
                current_container = name
                container_stack.append((name, line_no))
                symbols.append((name, "class", file_path, line_no, line_no, sig, "", None))
                # Base types
                inheritance = cls_m.group(2) or cls_m.group(3) or cls_m.group(4)
                if inheritance:
                    for base in re.split(r'[,()\s]+', inheritance):
                        base = base.strip()
                        if base and base not in ("Any", "Object", "Serializable"):
                            refs.append((name, base, file_path, line_no, "extends"))
                continue

            # Functions / Methods
            fn_m = func_regex.match(line)
            if fn_m:
                fn_name = fn_m.group(1)
                if fn_name not in ("if", "for", "while", "switch", "catch", "synchronized"):
                    sig = trimmed.rstrip(" {")
                    symbols.append((fn_name, "function", file_path, line_no, line_no, sig, "", current_container))

                    # Extract calls in this line
                    for call_m in call_regex.finditer(line):
                        callee = call_m.group(1)
                        if callee not in (fn_name, "if", "for", "while", "when", "return", "catch", "synchronized"):
                            refs.append((fn_name, callee, file_path, line_no, "call"))

        return symbols, refs

    @staticmethod
    def parse_smali(file_path, content):
        symbols = []
        refs = []
        lines = content.splitlines()

        class_regex = re.compile(r'^\.class\s+.*?\s+L([^;]+);')
        super_regex = re.compile(r'^\.super\s+L([^;]+);')
        method_regex = re.compile(r'^\.method\s+.*?\s+(\w+|<init>|<clinit>)\((.*?)\)(.*)')
        invoke_regex = re.compile(r'invoke-\w+.*?L([^;]+);->(\w+)\(')

        current_class = None
        current_method = None
        method_start = 1

        for idx, line in enumerate(lines):
            line_no = idx + 1
            trimmed = line.strip()

            cls_m = class_regex.match(trimmed)
            if cls_m:
                raw_cls = cls_m.group(1)
                simple_name = raw_cls.split("/")[-1]
                current_class = simple_name
                symbols.append((simple_name, "class", file_path, line_no, line_no, f"class {raw_cls.replace('/', '.')}", "", None))
                continue

            sup_m = super_regex.match(trimmed)
            if sup_m and current_class:
                sup_cls = sup_m.group(1).split("/")[-1]
                refs.append((current_class, sup_cls, file_path, line_no, "extends"))
                continue

            m_match = method_regex.match(trimmed)
            if m_match:
                m_name = m_match.group(1)
                m_args = m_match.group(2)
                m_ret = m_match.group(3)
                current_method = m_name
                method_start = line_no
                symbols.append((m_name, "method", file_path, line_no, line_no, f"{m_name}({m_args}){m_ret}", "", current_class))
                continue

            if trimmed == ".end method":
                current_method = None
                continue

            inv_m = invoke_regex.search(trimmed)
            if inv_m:
                target_cls = inv_m.group(1).split("/")[-1]
                target_method = inv_m.group(2)
                caller = current_method or current_class
                refs.append((caller, target_method, file_path, line_no, "call"))
                refs.append((caller, target_cls, file_path, line_no, "type_ref"))

        return symbols, refs

    @staticmethod
    def parse_generic_regex(file_path, content, lang):
        symbols = []
        refs = []
        lines = content.splitlines()

        fn_pattern = re.compile(r'^\s*(?:export\s+)?(?:async\s+)?(?:function|def|fn|func|sub)?\s*([a-zA-Z_]\w+)\s*\(')
        class_pattern = re.compile(r'^\s*(?:export\s+)?(?:class|struct|interface|type)\s+([a-zA-Z_]\w+)')
        import_pattern = re.compile(r'^\s*(?:import|#include|require|from)\s+["\'<]?([\w./_-]+)')

        for idx, line in enumerate(lines):
            line_no = idx + 1
            trimmed = line.strip()

            imp_m = import_pattern.match(trimmed)
            if imp_m:
                refs.append((None, imp_m.group(1), file_path, line_no, "import"))
                continue

            cls_m = class_pattern.match(trimmed)
            if cls_m:
                name = cls_m.group(1)
                symbols.append((name, "class", file_path, line_no, line_no, trimmed, "", None))
                continue

            fn_m = fn_pattern.match(trimmed)
            if fn_m:
                name = fn_m.group(1)
                if name not in ("if", "for", "while", "switch", "catch", "return"):
                    symbols.append((name, "function", file_path, line_no, line_no, trimmed, "", None))

        return symbols, refs


# ==============================================================================
# Code Indexer Engine
# ==============================================================================

class CodeIndexer:
    EXT_MAP = {
        ".py": "python",
        ".kt": "kotlin",
        ".java": "java",
        ".c": "c",
        ".cpp": "cpp",
        ".cc": "cpp",
        ".h": "c",
        ".hpp": "cpp",
        ".js": "javascript",
        ".ts": "typescript",
        ".tsx": "typescript",
        ".jsx": "javascript",
        ".smali": "smali",
        ".go": "go",
        ".rs": "rust",
    }

    IGNORE_DIRS = {
        ".git", ".gradle", "build", "target", "node_modules", ".idea",
        "dist", "out", "__pycache__", ".venv", "venv", ".codegraph"
    }

    def __init__(self, repo_dir, db_path=None):
        self.repo_dir = Path(repo_dir).resolve()
        if db_path is None:
            cg_dir = self.repo_dir / ".codegraph"
            cg_dir.mkdir(exist_ok=True)
            db_path = str(cg_dir / "index.db")
        self.db = CodeGraphDB(db_path)
        # 索引/查询串行化锁 + 后台初始索引进度状态
        self.lock = threading.Lock()
        self.indexing = False
        self.progress_done = 0
        self.progress_total = 0
        self.initial_index_done = False
        self.initial_index_error = None

    def status_text(self):
        if self.indexing:
            done, total = self.progress_done, self.progress_total
            pct = int(done * 100 / total) if total else 0
            return f"后台初始索引进行中: {done}/{total} 个文件 ({pct}%)，请稍后重试同步或查询"
        if not self.initial_index_done:
            return "初始索引尚未开始"
        if self.initial_index_error:
            return f"初始索引失败: {self.initial_index_error}"
        return "索引就绪"

    def start_background_initial_index(self):
        def run():
            self.indexing = True
            try:
                with self.lock:
                    self.scan_and_index(force=False, progress=True)
                self.initial_index_done = True
            except Exception as exc:
                self.initial_index_error = str(exc)
            finally:
                self.indexing = False

        threading.Thread(target=run, daemon=True, name="codegraph-initial-index").start()

    def scan_and_index(self, force=False, progress=False):
        t0 = time.time()
        indexed_count = 0
        total_symbols = 0

        # 预扫描统计文件总数，供进度展示
        candidates = []
        for root, dirs, files in os.walk(self.repo_dir):
            dirs[:] = [d for d in dirs if d not in self.IGNORE_DIRS]
            for file in files:
                ext = os.path.splitext(file)[1].lower()
                if self.EXT_MAP.get(ext):
                    candidates.append(os.path.join(root, file))
        total = len(candidates)
        self.progress_total = total
        self.progress_done = 0

        pending = 0
        for abs_path in candidates:
            self.progress_done += 1
            rel_path = os.path.relpath(abs_path, self.repo_dir).replace("\\", "/")

            try:
                mtime = os.path.getmtime(abs_path)
            except OSError:
                continue

            # 跳过异常大的生成文件（minified 产物/数据转储），解析性价比极低
            try:
                if os.path.getsize(abs_path) > MAX_INDEX_FILE_BYTES:
                    continue
            except OSError:
                continue

            # Check if already indexed
            if not force:
                cur = self.db.conn.cursor()
                cur.execute("SELECT mtime FROM files WHERE file_path = ?", (rel_path,))
                row = cur.fetchone()
                if row and abs(row["mtime"] - mtime) < 0.01:
                    continue

            # Read and parse
            try:
                with open(abs_path, "r", encoding="utf-8", errors="replace") as f:
                    content = f.read()
            except Exception:
                continue

            self.db.clear_file(rel_path)

            lang = self.EXT_MAP.get(os.path.splitext(abs_path)[1].lower())
            if lang == "python":
                symbols, refs = LanguageParser.parse_python(rel_path, content)
            elif lang in ("kotlin", "java"):
                symbols, refs = LanguageParser.parse_kotlin_java(rel_path, content)
            elif lang == "smali":
                symbols, refs = LanguageParser.parse_smali(rel_path, content)
            else:
                symbols, refs = LanguageParser.parse_generic_regex(rel_path, content, lang)

            self.db.insert_file(rel_path, lang, mtime, len(symbols))
            self.db.insert_symbols(symbols)
            self.db.insert_refs(refs)

            indexed_count += 1
            total_symbols += len(symbols)

            # 分批提交：大仓库上避免单事务过大，也让进度可观察
            pending += 1
            if pending >= 200:
                self.db.commit()
                pending = 0
            if progress and indexed_count % 25 == 0:
                pct = int(self.progress_done * 100 / total) if total else 100
                sys.stderr.write(f"[codegraph] indexing {self.progress_done}/{total} files ({pct}%), "
                                 f"{indexed_count} parsed, {total_symbols} symbols\n")
                sys.stderr.flush()

        self.db.commit()
        duration = time.time() - t0
        stats = self.db.get_stats()
        return {
            "indexed_files": indexed_count,
            "total_files": stats["files"],
            "total_symbols": stats["symbols"],
            "total_refs": stats["references"],
            "duration_sec": round(duration, 3),
        }

    def read_code_snippet(self, rel_path, line_start, max_lines=MAX_SNIPPET_LINES):
        abs_path = self.repo_dir / rel_path
        if not abs_path.is_file():
            return None
        try:
            with open(abs_path, "r", encoding="utf-8", errors="replace") as f:
                lines = f.readlines()
            start_idx = max(0, line_start - 1)
            end_idx = min(len(lines), start_idx + max_lines)
            numbered = [f"{i + 1:4d} | {lines[i].rstrip()}" for i in range(start_idx, end_idx)]
            if end_idx < len(lines):
                numbered.append(f"     ... (+{len(lines) - end_idx} more lines)")
            return "\n".join(numbered)
        except Exception:
            return None

    def explore(self, query, scope=None, max_depth=1):
        query = query.strip()
        matched_symbols = self.db.search_symbols(query, limit=5)

        if not matched_symbols:
            # Fallback search by regex or partial
            matched_symbols = self.db.search_symbols(query.split()[-1] if " " in query else query, limit=3)

        if not matched_symbols:
            return f"CodeGraph: 未在知识图谱中检索到符号 '{query}'。建议使用 codegraph_search 模糊搜索类名/函数名，或执行 codegraph_sync 同步最新代码。"

        output_blocks = []
        output_blocks.append(f"=== CodeGraph 探索结果: '{query}' (匹配到 {len(matched_symbols)} 个符号) ===")

        for sym in matched_symbols:
            name = sym["name"]
            kind = sym["kind"]
            file_path = sym["file_path"]
            line_start = sym["line_start"]
            sig = sym["signature"] or name

            if scope and not file_path.startswith(scope.replace("\\", "/")):
                continue

            block = []
            block.append(f"\n[{kind.upper()}] {name}  →  {file_path}:{line_start}")
            block.append(f"定义签名: {sig}")
            if sym.get("docstring"):
                block.append(f"文档说明: {sym['docstring']}")

            # Callers
            callers = self.db.get_callers(name, limit=8)
            if callers:
                caller_strs = [f"{c['caller']} ({c['file_path']}:{c['line_no']})" for c in callers if c['caller']]
                if caller_strs:
                    block.append("  ↳ 上游调用者 (Callers): " + ", ".join(caller_strs))

            # Callees
            callees = self.db.get_callees(name, limit=8)
            if callees:
                callee_strs = [f"{c['callee']} ({c['file_path']}:{c['line_no']})" for c in callees]
                if callee_strs:
                    block.append("  ↳ 下游调用 (Callees): " + ", ".join(callee_strs))

            # Impact summary
            impact = self.db.get_impact(name, max_depth=1)
            if impact:
                block.append(f"  ⚡ 影响面 (Direct Blast Radius): 直接关联 {len(impact)} 处外部调用")

            # Verbatim code snippet
            snippet = self.read_code_snippet(file_path, line_start, max_lines=18)
            if snippet:
                block.append("代码切片:\n```\n" + snippet + "\n```")

            output_blocks.append("\n".join(block))

        return "\n".join(output_blocks)


# ==============================================================================
# MCP Protocol Handlers
# ==============================================================================

TOOLS_DEF = [
    {
        "name": "codegraph_explore",
        "description": "【核心图谱导航】1 步检索代码库中的符号定义、调用拓扑（Call Graph）、代码切片与波及范围。遇到架构问答、函数链路梳理或定位实现时强制优先调用，替代盲目全量 grep。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "自然语言意图、类名、函数名或查询主题（如 'Interceptor' 或 'handleRequest'）"
                },
                "scope": {
                    "type": "string",
                    "description": "可选。限定搜索的子目录路径（如 'src/main' 或 'core/model'）"
                }
            },
            "required": ["query"]
        }
    },
    {
        "name": "codegraph_search",
        "description": "精确查找类、函数、方法或符号定义列表及其所在文件与行号。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "符号名称或关键字"},
                "kind": {"type": "string", "description": "可选。限定类型：class, function, method 等"}
            },
            "required": ["query"]
        }
    },
    {
        "name": "codegraph_callers",
        "description": "向上溯源：查找指定函数/方法在整个代码库中的所有调用者（Callers）。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "target": {"type": "string", "description": "目标函数或方法名"}
            },
            "required": ["target"]
        }
    },
    {
        "name": "codegraph_callees",
        "description": "向下追踪：列出指定函数或类调用的所有下游函数与引用（Callees）。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "target": {"type": "string", "description": "起始函数或类名"}
            },
            "required": ["target"]
        }
    },
    {
        "name": "codegraph_impact",
        "description": "波及范围（Blast Radius）分析：分析修改某个类或函数后，受影响的直接与间接上游模块列表。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "target": {"type": "string", "description": "要修改或重构的目标符号名"}
            },
            "required": ["target"]
        }
    },
    {
        "name": "codegraph_sync",
        "description": "增量同步工作区代码图谱数据库（.codegraph/index.db）。默认按文件 mtime 只解析改动的文件，通常几秒内完成；force=true 会强制全量重新解析所有文件，大仓库在手机上可能耗时数分钟，仅在怀疑索引损坏或首次建库失败时使用。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "force": {"type": "boolean", "description": "强制全量重建（很慢，仅在索引疑似损坏时使用）"}
            }
        }
    }
]


def response(req_id, result=None, error=None):
    val = {"jsonrpc": "2.0", "id": req_id}
    val["error" if error is not None else "result"] = error if error is not None else result
    return val


def main():
    parser = argparse.ArgumentParser(description="CodeGraph MCP Server")
    parser.add_argument("--repository", default=os.getcwd(), help="Repository root directory")
    args = parser.parse_args()

    indexer = CodeIndexer(args.repository)
    # 初始索引进后台线程：让 initialize/tools/list 立即可响应，避免大仓库上
    # 客户端等首个请求等到超时。索引进度写 stderr，状态可经 codegraph_sync/查询获取。
    indexer.start_background_initial_index()

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
            method = req.get("method")
            req_id = req.get("id")

            if method == "initialize":
                out = response(req_id, {
                    "protocolVersion": PROTOCOL_VERSION,
                    "capabilities": {"tools": {}},
                    "serverInfo": {"name": "minis-codegraph", "version": "1.0.0"}
                })
            elif method == "notifications/initialized":
                continue
            elif method == "tools/list":
                out = response(req_id, {"tools": TOOLS_DEF})
            elif method == "tools/call":
                params = req.get("params") or {}
                tool_name = params.get("name")
                tool_args = params.get("arguments") or {}

                try:
                    if tool_name == "codegraph_explore":
                        res_text = guarded_call(indexer, lambda: indexer.explore(
                            tool_args.get("query", ""), tool_args.get("scope")))
                    elif tool_name == "codegraph_search":
                        res_text = guarded_call(indexer, lambda: to_json_text(
                            indexer.db.search_symbols(tool_args.get("query", ""), tool_args.get("kind")),
                            "未检索到匹配符号"))
                    elif tool_name == "codegraph_callers":
                        res_text = guarded_call(indexer, lambda: to_json_text(
                            indexer.db.get_callers(tool_args.get("target", "")),
                            "未发现调用者引用"))
                    elif tool_name == "codegraph_callees":
                        res_text = guarded_call(indexer, lambda: to_json_text(
                            indexer.db.get_callees(tool_args.get("target", "")),
                            "未发现下游调用"))
                    elif tool_name == "codegraph_impact":
                        res_text = guarded_call(indexer, lambda: to_json_text(
                            indexer.db.get_impact(tool_args.get("target", "")),
                            "未发现外部受影响模块"))
                    elif tool_name == "codegraph_sync":
                        res_text = guarded_call(indexer, lambda: run_sync(indexer, tool_args))
                    else:
                        raise ValueError(f"未知工具: {tool_name}")

                    result = {"content": [{"type": "text", "text": res_text}], "isError": False}
                except IndexNotReady as exc:
                    # 索引未就绪 ≠ 工具失败：以正常结果返回进度与降级建议，
                    # 不计入 agent 侧失败轮次，防止首次大仓库查询触发熔断。
                    hint = ("本次调用不计为失败。可选择：① 稍后重试本工具（索引完成后自动可用）；"
                            "② 先用内置终端 ripgrep/grep 完成等价检索。")
                    result = {"content": [{"type": "text", "text": f"⏳ {str(exc)}\n{hint}"}], "isError": False}
                except Exception as exc:
                    result = {"content": [{"type": "text", "text": f"CodeGraph 工具执行异常: {str(exc)}"}], "isError": True}

                out = response(req_id, result)
            else:
                continue

            with STDOUT_WRITE_LOCK:
                sys.stdout.write(json.dumps(out, ensure_ascii=False) + "\n")
                sys.stdout.flush()

        except Exception as exc:
            err_resp = response(None, error={"code": -32603, "message": str(exc)})
            with STDOUT_WRITE_LOCK:
                sys.stdout.write(json.dumps(err_resp, ensure_ascii=False) + "\n")
                sys.stdout.flush()


def to_json_text(data, empty_hint):
    return json.dumps(data, ensure_ascii=False, indent=2) if data else empty_hint


class IndexNotReady(RuntimeError):
    """后台初始索引尚未完成，查询无法立即服务。
    调用方（tools/call 处理器）应将其转为非失败的进度提示返回，避免 agent 侧
    把首次查询计为工具失败、连锁触发连续失败熔断。"""


def guarded_call(indexer, fn):
    """触达图谱 DB 的工具共用锁：后台初始索引时等待其完成（默认 120s）。
    客户端 tools/call 超时为 10 分钟，等待窗口远小于此；典型仓库索引可在窗口
    内完成，等待后直接返回真实结果，避免首次查询硬失败。超时则抛 IndexNotReady，
    由调用方转为「非失败」的进度提示（提示先用 grep 规避或稍后重试）。"""
    if not indexer.lock.acquire(blocking=True, timeout=GUARDED_WAIT_SECONDS):
        raise IndexNotReady(indexer.status_text())
    try:
        return fn()
    finally:
        indexer.lock.release()


def run_sync(indexer, tool_args):
    res = indexer.scan_and_index(force=bool(tool_args.get("force", False)))
    return (f"图谱同步成功: 扫描更新 {res['indexed_files']} 个文件，"
            f"现有总符号数: {res['total_symbols']}，总引用数: {res['total_refs']} "
            f"(耗时 {res['duration_sec']}s)")


if __name__ == "__main__":
    main()
