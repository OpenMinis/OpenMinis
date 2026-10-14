#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# ==============================================================================
# Minis Ultra - 内置 Web 搜索与网页抓取 MCP 服务端 (stdio transport)
# ------------------------------------------------------------------------------
# 零依赖纯 Python 实现，开箱即用，无需 Node.js / npx 或外部 pip 包。
# 提供免 API Key 的多引擎网络搜索（Baidu / Bing / DuckDuckGo / Sogou）与网页正文提取。
#
# 协议：MCP stdio，逐行 JSON-RPC 交互。
# ==============================================================================
import html
import json
import os
import re
import sys
import urllib.parse
import urllib.request
import ssl

MAX_OUTPUT_CHARS = 30000
DEFAULT_TIMEOUT = 10
USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0"

# 创建忽略 SSL 证书校验的 context，防止手机沙箱内部分系统根证书不全导致请求报错
SSL_CONTEXT = ssl.create_default_context()
SSL_CONTEXT.check_hostname = False
SSL_CONTEXT.verify_mode = ssl.CERT_NONE


def http_get(url, headers=None, timeout=DEFAULT_TIMEOUT):
    """发起 HTTP GET 请求并返回文本内容。"""
    req_headers = {"User-Agent": USER_AGENT}
    if headers:
        req_headers.update(headers)
    req = urllib.request.Request(url, headers=req_headers)
    with urllib.request.urlopen(req, timeout=timeout, context=SSL_CONTEXT) as resp:
        charset = resp.headers.get_content_charset() or "utf-8"
        content_bytes = resp.read()
        try:
            return content_bytes.decode(charset, errors="replace")
        except Exception:
            return content_bytes.decode("utf-8", errors="replace")


def strip_html_tags(text):
    """剥离 HTML 标签，解码 HTML 实体并规范化空白。"""
    if not text:
        return ""
    # 移除 script 和 style
    text = re.sub(r"<(script|style|svg|header|footer|nav)[\s\S]*?</\1>", " ", text, flags=re.IGNORECASE)
    # 转换为段落换行
    text = re.sub(r"<(?:p|div|br|li|h[1-6])[\s\S]*?>", "\n", text, flags=re.IGNORECASE)
    # 剥离其余标签
    text = re.sub(r"<[^>]+>", " ", text)
    text = html.unescape(text)
    # 规范化多余空行和空白
    lines = [line.strip() for line in text.splitlines()]
    text = "\n".join(line for line in lines if line)
    return text


# ============================== 搜索引擎实现 ==============================

def search_baidu(query, limit=5):
    """Baidu 网页搜索。"""
    encoded_query = urllib.parse.quote(query)
    url = f"https://www.baidu.com/s?wd={encoded_query}&rn={max(limit, 10)}"
    html_content = http_get(url, headers={
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
    })
    results = []

    # 提取搜索卡片
    matches = re.findall(
        r'<h3[^>]*class="[^"]*t[^"]*"[^>]*>\s*<a[^>]*href="([^"]+)"[^>]*>([\s\S]*?)</a>\s*</h3>[\s\S]*?(?:<div[^>]*class="[^"]*(?:c-abstract|c-span-last|content-right_8Zs40)[^"]*"[^>]*>([\s\S]*?)</div>)?',
        html_content,
        flags=re.IGNORECASE
    )

    for link, title_raw, snippet_raw in matches:
        title = strip_html_tags(title_raw).strip()
        snippet = strip_html_tags(snippet_raw).strip() if snippet_raw else ""
        if title and link:
            results.append({
                "title": title,
                "url": link,
                "snippet": snippet or "无摘要",
            })
        if len(results) >= limit:
            break

    # 若正则未匹配到完整卡片，回退通用 h3 抽取
    if not results:
        h3_matches = re.findall(r'<h3[\s\S]*?<a[^>]*href="([^"]+)"[^>]*>([\s\S]*?)</a>', html_content, flags=re.IGNORECASE)
        for link, title_raw in h3_matches:
            title = strip_html_tags(title_raw).strip()
            if title and "baidu.com" not in title and link:
                results.append({"title": title, "url": link, "snippet": ""})
            if len(results) >= limit:
                break

    return results


def search_bing(query, limit=5):
    """Bing 网页搜索。"""
    encoded_query = urllib.parse.quote(query)
    url = f"https://cn.bing.com/search?q={encoded_query}&count={max(limit, 10)}"
    html_content = http_get(url, headers={
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
    })
    results = []

    items = re.findall(
        r'<li[^>]*class="b_algo"[^>]*>[\s\S]*?<h2><a[^>]*href="([^"]+)"[^>]*>([\s\S]*?)</a></h2>[\s\S]*?(?:<div[^>]*class="b_caption"[^>]*>[\s\S]*?<p[^>]*>([\s\S]*?)</p>)?',
        html_content,
        flags=re.IGNORECASE
    )

    for link, title_raw, snippet_raw in items:
        title = strip_html_tags(title_raw).strip()
        snippet = strip_html_tags(snippet_raw).strip() if snippet_raw else ""
        if title and link:
            results.append({
                "title": title,
                "url": link,
                "snippet": snippet or "无摘要",
            })
        if len(results) >= limit:
            break

    return results


def search_duckduckgo(query, limit=5):
    """DuckDuckGo HTML 免 API 搜索。"""
    encoded_query = urllib.parse.quote(query)
    url = f"https://html.duckduckgo.com/html/?q={encoded_query}"
    html_content = http_get(url)
    results = []

    matches = re.findall(
        r'<a[^>]*class="result__url"[^>]*href="([^"]+)"[^>]*>[\s\S]*?<a[^>]*class="result__snippet"[^>]*>([\s\S]*?)</a>',
        html_content,
        flags=re.IGNORECASE
    )

    for link, snippet_raw in matches:
        actual_url = link
        if "uddg=" in link:
            match_uddg = re.search(r'uddg=([^&]+)', link)
            if match_uddg:
                actual_url = urllib.parse.unquote(match_uddg.group(1))
        snippet = strip_html_tags(snippet_raw).strip()
        results.append({
            "title": snippet[:40] + "..." if len(snippet) > 40 else snippet,
            "url": actual_url,
            "snippet": snippet,
        })
        if len(results) >= limit:
            break

    return results


# ============================== MCP 工具分发 ==============================

def tool_web_search(args):
    """执行网络检索并返回格式化结果。"""
    query = (args.get("query") or args.get("q") or "").strip()
    if not query:
        return False, "缺少参数：query"

    engine = (args.get("engine") or os.environ.get("DEFAULT_SEARCH_ENGINE") or "baidu").lower().strip()
    try:
        limit = int(args.get("limit") or 5)
    except Exception:
        limit = 5
    limit = max(1, min(limit, 15))

    results = []
    error_msg = ""

    # 首选引擎查询
    try:
        if engine == "bing":
            results = search_bing(query, limit)
        elif engine in ("duckduckgo", "ddg"):
            results = search_duckduckgo(query, limit)
        else:
            results = search_baidu(query, limit)
    except Exception as e:
        error_msg = f"{engine} 搜索异常: {e}"

    # 若首选引擎失败或无结果，自动回退到 Bing / Baidu 交叉重试
    if not results:
        fallback_engines = ["bing", "baidu", "duckduckgo"]
        for fb in fallback_engines:
            if fb == engine:
                continue
            try:
                if fb == "bing":
                    results = search_bing(query, limit)
                elif fb == "baidu":
                    results = search_baidu(query, limit)
                elif fb == "duckduckgo":
                    results = search_duckduckgo(query, limit)
                if results:
                    break
            except Exception:
                continue

    if not results:
        return False, f"未搜索到与「{query}」相关的结果。" + (f" ({error_msg})" if error_msg else "")

    output_lines = [f"### 搜索结果（关键词：{query}，共 {len(results)} 条）：\n"]
    for idx, item in enumerate(results, start=1):
        output_lines.append(f"{idx}. **[{item['title']}]({item['url']})**")
        if item.get("snippet"):
            output_lines.append(f"   > {item['snippet']}\n")
        else:
            output_lines.append(f"   链接: {item['url']}\n")

    return True, "\n".join(output_lines)


def tool_fetch_content(args):
    """抓取指定 URL 网页并提取正文内容。"""
    url = (args.get("url") or "").strip()
    if not url:
        return False, "缺少参数：url"
    if not (url.startswith("http://") or url.startswith("https://")):
        url = "https://" + url

    try:
        max_length = int(args.get("max_length") or 6000)
    except Exception:
        max_length = 6000

    try:
        raw_html = http_get(url, timeout=12)
        text = strip_html_tags(raw_html)
        if len(text) > max_length:
            text = text[:max_length] + "\n\n...[正文过长已截断]..."
        return True, f"### 网页内容提取（{url}）：\n\n{text}"
    except Exception as e:
        return False, f"抓取网页正文失败 ({url}): {e}"


TOOLS = {
    "web_search": {
        "description": "免 API Key 的网络搜索工具，支持百度、Bing 等多引擎搜索，返回标题、摘要与链接",
        "inputSchema": {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "搜索关键词或自然语言问题"},
                "engine": {
                    "type": "string",
                    "description": "搜索引擎选择（可选：baidu / bing / duckduckgo，默认 baidu）",
                    "enum": ["baidu", "bing", "duckduckgo"]
                },
                "limit": {"type": "integer", "description": "返回结果数量（默认 5，范围 1-15）"},
            },
            "required": ["query"],
        },
        "handler": tool_web_search,
    },
    "fetch_web_content": {
        "description": "抓取目标网页链接的正文内容并自动清洗为易读文本",
        "inputSchema": {
            "type": "object",
            "properties": {
                "url": {"type": "string", "description": "目标网页 URL（以 http:// 或 https:// 开头）"},
                "max_length": {"type": "integer", "description": "返回最大字符数（默认 6000）"},
            },
            "required": ["url"],
        },
        "handler": tool_fetch_content,
    },
}


# ============================== stdio 主循环 ==============================

def rpc_response(req_id, result):
    return {"jsonrpc": "2.0", "id": req_id, "result": result}


def handle_line(line):
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
            "serverInfo": {"name": "minis-websearch-mcp", "version": "1.0.0"},
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
                "content": [{"type": "text", "text": f"未知工具: {name}"}],
                "isError": True,
            })
        try:
            ok, text = spec["handler"](args)
        except Exception as e:
            ok, text = False, f"工具执行异常: {e}"
        return rpc_response(req_id, {
            "content": [{"type": "text", "text": text}],
            "isError": not ok,
        })
    return None


def main():
    for line in sys.stdin:
        resp = handle_line(line)
        if resp is not None:
            sys.stdout.write(json.dumps(resp, ensure_ascii=False) + "\n")
            sys.stdout.flush()


if __name__ == "__main__":
    main()