package com.openminis.app.browser

/**
 * Injectable JavaScript for browser_use actions.
 * Each function returns a self-executing IIFE that JSON.stringify()s the result.
 * Ported 1:1 from iOS BrowserUseJavaScript.swift.
 */
object BrowserUseJS {

    /** Safely escape a string for embedding in JavaScript source (single-quoted). */
    fun jsQuote(s: String): String = buildString {
        for (ch in s) {
            when (ch) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }

    // -- Click --

    fun click(selector: String): String = """
        (function() {
            var el = document.querySelector('${jsQuote(selector)}');
            if (!el) return JSON.stringify({error: 'Element not found: ${jsQuote(selector)}'});
            if (el.disabled) return JSON.stringify({error: 'Element is disabled', tag: el.tagName});
            var bOpts = {bubbles: true, cancelable: true, view: window};
            var nbOpts = {bubbles: false, cancelable: true, view: window};
            el.dispatchEvent(new MouseEvent('mouseover', bOpts));
            el.dispatchEvent(new MouseEvent('mouseenter', nbOpts));
            el.dispatchEvent(new MouseEvent('mousemove', bOpts));
            el.dispatchEvent(new MouseEvent('mousedown', bOpts));
            el.dispatchEvent(new MouseEvent('mouseup', bOpts));
            el.click();
            el.dispatchEvent(new MouseEvent('mouseleave', nbOpts));
            el.dispatchEvent(new MouseEvent('mouseout', bOpts));
            return JSON.stringify({clicked: true, tag: el.tagName, text: (el.innerText || '').substring(0, 100)});
        })()
    """.trimIndent()

    fun clickCoordinate(x: Int, y: Int): String = """
        (function() {
            var el = document.elementFromPoint($x, $y);
            if (!el) return JSON.stringify({error: 'No element at ($x, $y)'});
            if (el.disabled) return JSON.stringify({error: 'Element is disabled', tag: el.tagName, x: $x, y: $y});
            var bOpts = {bubbles: true, cancelable: true, view: window};
            var nbOpts = {bubbles: false, cancelable: true, view: window};
            el.dispatchEvent(new MouseEvent('mouseover', bOpts));
            el.dispatchEvent(new MouseEvent('mouseenter', nbOpts));
            el.dispatchEvent(new MouseEvent('mousemove', bOpts));
            el.dispatchEvent(new MouseEvent('mousedown', bOpts));
            el.dispatchEvent(new MouseEvent('mouseup', bOpts));
            el.click();
            el.dispatchEvent(new MouseEvent('mouseleave', nbOpts));
            el.dispatchEvent(new MouseEvent('mouseout', bOpts));
            return JSON.stringify({clicked: true, tag: el.tagName, x: $x, y: $y, text: (el.innerText || '').substring(0, 100)});
        })()
    """.trimIndent()

    // -- Type --

    fun type(selector: String, text: String): String = """
        (function() {
            var el = document.querySelector('${jsQuote(selector)}');
            if (!el) return JSON.stringify({error: 'Element not found: ${jsQuote(selector)}'});
            el.focus();
            if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
                var nativeSetter = Object.getOwnPropertyDescriptor(
                    el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype, 'value'
                );
                if (nativeSetter && nativeSetter.set) {
                    nativeSetter.set.call(el, '${jsQuote(text)}');
                } else {
                    el.value = '${jsQuote(text)}';
                }
            } else {
                el.innerText = '${jsQuote(text)}';
            }
            var chars = '${jsQuote(text)}';
            for (var i = 0; i < chars.length; i++) {
                var c = chars[i];
                el.dispatchEvent(new KeyboardEvent('keydown', {key: c, bubbles: true}));
                el.dispatchEvent(new KeyboardEvent('keypress', {key: c, bubbles: true}));
                el.dispatchEvent(new InputEvent('input', {data: c, inputType: 'insertText', bubbles: true}));
                el.dispatchEvent(new KeyboardEvent('keyup', {key: c, bubbles: true}));
            }
            el.dispatchEvent(new Event('change', {bubbles: true}));
            try {
                if (window.angular) {
                    var ngEl = window.angular.element(el);
                    var scope = ngEl.scope() || (ngEl.injector && ngEl.injector().get('${'$'}rootScope'));
                    if (scope && !scope.${'$'}${'$'}phase) scope.${'$'}apply();
                }
            } catch(e) {}
            try {
                if (el.__vue__) el.__vue__.${'$'}forceUpdate();
                if (el._vei || el.__vueParentComponent) el.dispatchEvent(new Event('input', {bubbles: true}));
            } catch(e) {}
            if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable) {
                el.dispatchEvent(new FocusEvent('blur', {bubbles: true, relatedTarget: null}));
                el.dispatchEvent(new FocusEvent('focusout', {bubbles: true, relatedTarget: null}));
            }
            return JSON.stringify({typed: true, selector: '${jsQuote(selector)}', length: chars.length});
        })()
    """.trimIndent()

    // -- Get Text --

    fun getText(selector: String?): String {
        if (selector != null) {
            val sel = jsQuote(selector)
            return """
                (function() {
                    var el = document.querySelector('$sel');
                    if (!el) return JSON.stringify({error: 'Element not found: $sel'});
                    var innerTextVal = el.innerText || '';
                    var textContentVal = el.textContent || '';
                    var text = innerTextVal.substring(0, 10000);
                    return JSON.stringify({text: text, length: text.length});
                })()
            """.trimIndent()
        }
        return """
            (function() {
                var innerTextVal = document.body.innerText || '';
                var text = innerTextVal.substring(0, 10000);
                return JSON.stringify({text: text, length: text.length, url: location.href, title: document.title});
            })()
        """.trimIndent()
    }

    // -- Get Readable --

    fun getReadable(): String = """
        (function() {
            var candidateSelectors = [
                'article', '[role="main"]', 'main', '.post-content',
                '.article-body', '.entry-content', '#content', '.content'
            ];
            var el = null;
            var matchedSelector = null;
            for (var i = 0; i < candidateSelectors.length; i++) {
                var found = document.querySelector(candidateSelectors[i]);
                if (found && window.getComputedStyle(found).display !== 'none' && (found.innerText || '').length > 0) {
                    el = found; matchedSelector = candidateSelectors[i]; break;
                }
            }
            if (!el) { el = document.body; matchedSelector = 'document.body (fallback)'; }
            var title = document.title || '';
            var innerTextVal = el.innerText || '';
            var text = innerTextVal.replace(/\s+/g, ' ').trim().substring(0, 15000);
            return JSON.stringify({title: title, text: text, length: text.length, source: matchedSelector});
        })()
    """.trimIndent()

    // -- Scroll --

    fun scroll(direction: ScrollDirection, amount: Int, selector: String?): String {
        val pixels = if (direction == ScrollDirection.DOWN) amount else -amount
        val dir = direction.value
        val selectorJS = if (selector != null) "'${jsQuote(selector)}'" else "null"
        return """
            (function() {
                var targetSelector = $selectorJS;
                var target = null;
                var scrolledElement = 'window';
                if (targetSelector) {
                    target = document.querySelector(targetSelector);
                    if (!target) return JSON.stringify({error: 'Element not found: ' + targetSelector});
                } else {
                    var beforeY = window.scrollY;
                    window.scrollBy(0, $pixels);
                    if (window.scrollY !== beforeY) {
                        var sh = document.documentElement.scrollHeight || document.body.scrollHeight;
                        return JSON.stringify({scrolled: true, element: 'window', direction: '$dir', amount: $amount, scrollY: window.scrollY, scrollHeight: sh, viewportHeight: window.innerHeight});
                    }
                    var best = null;
                    var bestArea = 0;
                    function walk(el, depth) {
                        if (depth > 10) return;
                        var children = el.children;
                        for (var i = 0; i < children.length; i++) {
                            var child = children[i];
                            var st = window.getComputedStyle(child);
                            var oy = st.overflowY;
                            if ((oy === 'auto' || oy === 'scroll') && child.scrollHeight > child.clientHeight + 5) {
                                var area = child.clientWidth * child.clientHeight;
                                if (area > bestArea) { best = child; bestArea = area; }
                            }
                            walk(child, depth + 1);
                        }
                    }
                    walk(document.body, 0);
                    if (best) { target = best; scrolledElement = best.tagName.toLowerCase(); }
                    else {
                        document.documentElement.scrollTop += $pixels;
                        return JSON.stringify({scrolled: true, element: 'document.documentElement', direction: '$dir', amount: $amount, scrollY: document.documentElement.scrollTop});
                    }
                }
                if (target) {
                    target.scrollBy(0, $pixels);
                    return JSON.stringify({scrolled: true, element: scrolledElement, direction: '$dir', amount: $amount, scrollTop: target.scrollTop, scrollHeight: target.scrollHeight, clientHeight: target.clientHeight});
                }
                return JSON.stringify({scrolled: false, error: 'No scrollable target found'});
            })()
        """.trimIndent()
    }

    // -- Hover --

    fun hover(selector: String): String = """
        (function() {
            var el = document.querySelector('${jsQuote(selector)}');
            if (!el) return JSON.stringify({error: 'Element not found: ${jsQuote(selector)}'});
            el.dispatchEvent(new MouseEvent('mouseenter', {bubbles: true}));
            el.dispatchEvent(new MouseEvent('mouseover', {bubbles: true}));
            return JSON.stringify({hovered: true, tag: el.tagName, text: (el.innerText || '').substring(0, 100)});
        })()
    """.trimIndent()

    // -- Find Elements --

    fun findElements(selector: String): String = """
        (function() {
            var els = document.querySelectorAll('${jsQuote(selector)}');
            var results = [];
            var limit = Math.min(els.length, 20);
            var scrollX = window.scrollX || window.pageXOffset || 0;
            var scrollY = window.scrollY || window.pageYOffset || 0;
            for (var i = 0; i < limit; i++) {
                var el = els[i];
                var rect = el.getBoundingClientRect();
                results.push({
                    index: i, tag: el.tagName, id: el.id || null,
                    className: el.className || null,
                    text: (el.innerText || '').substring(0, 80),
                    href: el.href || null,
                    rect: {x: Math.round(rect.x), y: Math.round(rect.y), width: Math.round(rect.width), height: Math.round(rect.height), pageX: Math.round(rect.x + scrollX), pageY: Math.round(rect.y + scrollY), visible: rect.width > 0 && rect.height > 0 && rect.top < window.innerHeight && rect.bottom > 0}
                });
            }
            return JSON.stringify({count: els.length, shown: limit, elements: results});
        })()
    """.trimIndent()

    // -- Get Page Info --

    fun getPageInfo(): String = """
        (function() {
            return JSON.stringify({
                url: window.location.href,
                title: document.title,
                scrollY: window.scrollY,
                scrollHeight: document.body.scrollHeight,
                viewportWidth: window.innerWidth,
                viewportHeight: window.innerHeight,
                readyState: document.readyState,
                forms: document.forms.length,
                links: document.links.length,
                images: document.images.length
            });
        })()
    """.trimIndent()

    // -- Get Backbone --

    fun getBackbone(maxDepth: Int): String = """
        (function() {
            var MAX_DEPTH = $maxDepth;
            var MERGE_THRESHOLD = 10;
            var MIN_SIZE = 8;
            function isVisible(el) {
                if (!el.getBoundingClientRect) return false;
                var st = window.getComputedStyle(el);
                if (st.display === 'none' || st.visibility === 'hidden' || st.opacity === '0') return false;
                var r = el.getBoundingClientRect();
                return r.width >= MIN_SIZE && r.height >= MIN_SIZE;
            }
            var SEMANTIC_TAGS = new Set(['IMG','SVG','VIDEO','CANVAS','INPUT','TEXTAREA','BUTTON','A','SELECT','IFRAME']);
            var SKIP_TAGS = new Set(['SCRIPT','STYLE','NOSCRIPT','BR','HR','META','LINK','TEMPLATE']);
            function isLeaf(el) {
                if (SKIP_TAGS.has(el.tagName)) return false;
                if (SEMANTIC_TAGS.has(el.tagName)) return true;
                var children = el.children;
                for (var i = 0; i < children.length; i++) {
                    if (!SKIP_TAGS.has(children[i].tagName) && isVisible(children[i])) return false;
                }
                return true;
            }
            function rectsClose(a, b) {
                return Math.abs(a.left - b.left) <= MERGE_THRESHOLD && Math.abs(a.top - b.top) <= MERGE_THRESHOLD &&
                       Math.abs(a.right - b.right) <= MERGE_THRESHOLD && Math.abs(a.bottom - b.bottom) <= MERGE_THRESHOLD;
            }
            function shortSelector(el) {
                if (el.id) return '#' + el.id;
                var path = [];
                var cur = el;
                while (cur && cur !== document.body && cur !== document.documentElement) {
                    if (cur.id) { path.unshift('#' + cur.id); break; }
                    var seg = cur.tagName.toLowerCase();
                    var parent = cur.parentElement;
                    if (parent) {
                        var siblings = parent.children;
                        var sameTag = 0, idx = 0;
                        for (var i = 0; i < siblings.length; i++) {
                            if (siblings[i].tagName === cur.tagName) { sameTag++; if (siblings[i] === cur) idx = sameTag; }
                        }
                        if (sameTag > 1) seg += ':nth-of-type(' + idx + ')';
                    }
                    path.unshift(seg);
                    cur = parent;
                }
                if (path.length > 3 && path[0].charAt(0) === '#') path = [path[0]].concat(path.slice(-2));
                else if (path.length > 2) path = path.slice(-2);
                return path.join(' > ');
            }
            function getText(el) { return ((el.innerText || el.textContent || '').trim()).substring(0, 120); }
            function hasMeaningfulContent(el, rect) {
                if (rect.width < 12 || rect.height < 12) return false;
                if (getText(el).length > 1) return true;
                if (['INPUT','TEXTAREA','SELECT','BUTTON'].indexOf(el.tagName) >= 0) return true;
                if (el.tagName === 'IMG' && rect.width >= 24 && rect.height >= 24) return true;
                if (['VIDEO','CANVAS','IFRAME'].indexOf(el.tagName) >= 0) return true;
                if (el.tagName === 'A' && el.href && el.href.indexOf('javascript:') !== 0) return true;
                return false;
            }
            var allEls = document.body.querySelectorAll('*');
            var leaves = [];
            for (var i = 0; i < allEls.length; i++) {
                var el = allEls[i];
                if (isVisible(el) && isLeaf(el)) {
                    var r = el.getBoundingClientRect();
                    if (hasMeaningfulContent(el, r)) leaves.push(el);
                }
            }
            var seen = new Set();
            var representatives = [];
            var mergedCount = 0;
            for (var i = 0; i < leaves.length; i++) {
                var rep = leaves[i];
                var cur = rep.parentElement;
                while (cur && cur !== document.body && cur !== document.documentElement) {
                    if (!isVisible(cur)) break;
                    if (rectsClose(rep.getBoundingClientRect(), cur.getBoundingClientRect())) { rep = cur; mergedCount++; }
                    else break;
                    cur = cur.parentElement;
                }
                if (!seen.has(rep)) { seen.add(rep); representatives.push(rep); }
            }
            var ancestorMap = new Map();
            for (var i = 0; i < representatives.length; i++) {
                var cur = representatives[i].parentElement;
                var depth = 0;
                while (cur && cur !== document.body && cur !== document.documentElement && depth < 6) {
                    if (!ancestorMap.has(cur)) ancestorMap.set(cur, 0);
                    ancestorMap.set(cur, ancestorMap.get(cur) + 1);
                    cur = cur.parentElement; depth++;
                }
            }
            var groupNodes = [];
            ancestorMap.forEach(function(count, el) {
                if (count >= 2 && !seen.has(el) && isVisible(el)) { seen.add(el); groupNodes.push(el); }
            });
            var allReps = representatives.concat(groupNodes);
            var nodes = allReps.map(function(el) {
                var r = el.getBoundingClientRect();
                var info = {el: el, tag: el.tagName, rect: {x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height)}, area: r.width * r.height, children: [], isGroup: groupNodes.indexOf(el) >= 0};
                if (el.id) info.id = el.id;
                var cn = el.className && typeof el.className === 'string' ? el.className.trim().split(/\s+/).slice(0, 2).join(' ') : '';
                if (cn) info.className = cn;
                info.selector = shortSelector(el);
                if (!info.isGroup) { var txt = getText(el); if (txt) info.text = txt; }
                if (el.tagName === 'IMG') { info.src = (el.src || '').substring(0, 120); info.imgSize = el.naturalWidth + 'x' + el.naturalHeight; }
                if (el.tagName === 'A' && el.href && el.href.indexOf('javascript:') !== 0) {
                    try { var u = new URL(el.href); info.href = u.pathname + (u.search ? u.search.substring(0, 40) : ''); } catch(e) { info.href = el.href.substring(0, 80); }
                }
                // [T-android-browser-password-redact] A password field's value is
                // redacted at capture, so the plaintext never enters the node tree
                // (and never reaches the model). Whether it is filled is still
                // reported: the agent needs that to drive a login form. iOS parity
                // (BrowserUseJavaScript.swift).
                if (['INPUT','TEXTAREA','SELECT'].indexOf(el.tagName) >= 0) { info.inputType = el.type || null; if (el.value) info.value = (el.type === 'password') ? '[redacted]' : el.value.substring(0, 60); if (el.placeholder) info.placeholder = el.placeholder.substring(0, 60); }
                var role = el.getAttribute('role'); if (role) info.role = role;
                return info;
            });
            nodes.sort(function(a, b) { return b.area - a.area; });
            function domContains(a, b) { return a.el.contains(b.el); }
            var roots = [];
            for (var i = 0; i < nodes.length; i++) {
                var node = nodes[i]; var bestParent = null; var bestArea = Infinity;
                for (var j = 0; j < i; j++) {
                    if (nodes[j].el !== node.el && domContains(nodes[j], node) && nodes[j].area < bestArea) { bestParent = nodes[j]; bestArea = nodes[j].area; }
                }
                if (bestParent) bestParent.children.push(node); else roots.push(node);
            }
            function prune(node) {
                for (var i = 0; i < node.children.length; i++) prune(node.children[i]);
                if (node.isGroup && node.children.length === 1 && !node.text) {
                    var child = node.children[0];
                    node.children = child.children;
                    if (child.text && !node.text) node.text = child.text;
                    if (child.href && !node.href) node.href = child.href;
                    if (child.src) { node.src = child.src; node.imgSize = child.imgSize; }
                    if (!node.id && child.id) node.id = child.id;
                    node.tag = child.tag; node.selector = child.selector; node.isGroup = false;
                }
                node.children = node.children.filter(function(c) { return !(c.isGroup && c.children.length === 0 && !c.text); });
            }
            for (var i = 0; i < roots.length; i++) prune(roots[i]);
            roots = roots.filter(function(r) { return !(r.isGroup && r.children.length === 0 && !r.text); });
            function trimDepth(node, depth) { if (depth >= MAX_DEPTH) { node.children = []; return; } for (var i = 0; i < node.children.length; i++) trimDepth(node.children[i], depth + 1); }
            for (var i = 0; i < roots.length; i++) trimDepth(roots[i], 1);
            var totalNodes = 0, maxD = 0;
            var sX = window.scrollX || window.pageXOffset || 0;
            var sY = window.scrollY || window.pageYOffset || 0;
            function ser(node, depth) {
                totalNodes++; if (depth > maxD) maxD = depth;
                var o = {tag: node.tag};
                if (node.id) o.id = node.id;
                if (node.className) o.cls = node.className;
                o.sel = node.selector;
                if (node.role) o.role = node.role;
                if (node.text) o.text = node.text;
                if (node.href) o.href = node.href;
                if (node.src) o.img = node.imgSize + ' ' + node.src;
                if (node.inputType !== undefined) o.input = node.inputType + (node.value ? ' val=' + node.value : '') + (node.placeholder ? ' ph=' + node.placeholder : '');
                o.rect = node.rect.x + ',' + node.rect.y + ' ' + node.rect.w + 'x' + node.rect.h;
                o.pageXY = (node.rect.x + sX) + ',' + (node.rect.y + sY);
                if (node.children.length > 0) o.children = node.children.map(function(c) { return ser(c, depth + 1); });
                return o;
            }
            var tree = roots.map(function(r) { return ser(r, 1); });
            return JSON.stringify({backbone: tree, nodeCount: totalNodes, depth: maxD, merged: mergedCount});
        })()
    """.trimIndent()

    // -- Fetch (async IIFE) --

    fun fetch(url: String): String {
        val escaped = url.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return """
            (async function() {
                try {
                    const resp = await fetch("$escaped");
                    const buf = await resp.arrayBuffer();
                    const bytes = new Uint8Array(buf);
                    let binary = '';
                    for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
                    const b64 = btoa(binary);
                    return JSON.stringify({
                        base64: b64,
                        contentType: resp.headers.get('content-type') || '',
                        contentDisposition: resp.headers.get('content-disposition') || '',
                        status: resp.status,
                        url: resp.url,
                        size: bytes.length
                    });
                } catch(e) {
                    return JSON.stringify({error: e.message});
                }
            })()
        """.trimIndent()
    }
}
