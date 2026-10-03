import Foundation

/// All injectable JavaScript for browser_use actions.
/// Each function returns a self-executing IIFE that JSON.stringify()s the result.
enum BrowserUseJS {

    /// Safely escape a string for embedding in JavaScript source.
    static func jsQuote(_ s: String) -> String {
        var result = ""
        for ch in s {
            switch ch {
            case "\\": result += "\\\\"
            case "'":  result += "\\'"
            case "\"": result += "\\\""
            case "\n": result += "\\n"
            case "\r": result += "\\r"
            case "\t": result += "\\t"
            default:   result.append(ch)
            }
        }
        return result
    }

    // MARK: - Click

    static func click(selector: String) -> String {
        """
        (function() {
            var el = document.querySelector('\(jsQuote(selector))');
            if (!el) return JSON.stringify({error: 'Element not found: \(jsQuote(selector))'});
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
        """
    }

    static func clickCoordinate(x: Int, y: Int) -> String {
        """
        (function() {
            var el = document.elementFromPoint(\(x), \(y));
            if (!el) return JSON.stringify({error: 'No element at (\(x), \(y))'});
            if (el.disabled) return JSON.stringify({error: 'Element is disabled', tag: el.tagName, x: \(x), y: \(y)});
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
            return JSON.stringify({clicked: true, tag: el.tagName, x: \(x), y: \(y), text: (el.innerText || '').substring(0, 100)});
        })()
        """
    }

    // MARK: - Type

    static func type(selector: String, text: String) -> String {
        """
        (function() {
            var el = document.querySelector('\(jsQuote(selector))');
            if (!el) return JSON.stringify({error: 'Element not found: \(jsQuote(selector))'});
            el.focus();
            if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
                // Use native setter to bypass React/Vue controlled input guards
                var nativeSetter = Object.getOwnPropertyDescriptor(
                    el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype, 'value'
                );
                if (nativeSetter && nativeSetter.set) {
                    nativeSetter.set.call(el, '\(jsQuote(text))');
                } else {
                    el.value = '\(jsQuote(text))';
                }
            } else {
                el.innerText = '\(jsQuote(text))';
            }
            // Simulate keyboard events for each character
            var chars = '\(jsQuote(text))';
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
                    var scope = ngEl.scope() || (ngEl.injector && ngEl.injector().get('$rootScope'));
                    if (scope && !scope.$$phase) scope.$apply();
                }
            } catch(e) {}
            try {
                if (el.__vue__) el.__vue__.$forceUpdate();
                if (el._vei || el.__vueParentComponent) el.dispatchEvent(new Event('input', {bubbles: true}));
            } catch(e) {}
            if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable) {
                el.dispatchEvent(new FocusEvent('blur', {bubbles: true, relatedTarget: null}));
                el.dispatchEvent(new FocusEvent('focusout', {bubbles: true, relatedTarget: null}));
            }
            return JSON.stringify({typed: true, selector: '\(jsQuote(selector))', length: chars.length});
        })()
        """
    }

    // MARK: - Get Text

    static func getText(selector: String?) -> String {
        if let sel = selector {
            return """
            (function() {
                var el = document.querySelector('\(jsQuote(sel))');
                if (!el) return JSON.stringify({error: 'Element not found: \(jsQuote(sel))'});
                var innerTextVal = el.innerText || '';
                var textContentVal = el.textContent || '';
                var text = innerTextVal.substring(0, 10000);
                var debug = {
                    selector: '\(jsQuote(sel))',
                    tag: el.tagName,
                    id: el.id || null,
                    className: el.className ? el.className.split(' ').slice(0,3).join(' ') : null,
                    innerTextLength: innerTextVal.length,
                    textContentLength: textContentVal.length,
                    innerTextPreview: innerTextVal.substring(0, 200),
                    textContentPreview: textContentVal.replace(/\\s+/g, ' ').trim().substring(0, 200),
                    bodyInnerTextLength: (document.body.innerText || '').length,
                    bodyTextContentLength: (document.body.textContent || '').length,
                    readyState: document.readyState,
                    scrollHeight: document.body.scrollHeight,
                    viewportHeight: window.innerHeight
                };
                console.log('[getText selector] debug:', JSON.stringify(debug));
                return JSON.stringify({text: text, length: text.length, debug: debug});
            })()
            """
        }
        return """
        (function() {
            var innerTextVal = document.body.innerText || '';
            var textContentVal = document.body.textContent || '';
            var text = innerTextVal.substring(0, 10000);
            var allEls = document.body.querySelectorAll('*');
            var visibleCount = 0;
            var hiddenCount = 0;
            for (var i = 0; i < Math.min(allEls.length, 500); i++) {
                var st = window.getComputedStyle(allEls[i]);
                if (st.display === 'none' || st.visibility === 'hidden') hiddenCount++;
                else visibleCount++;
            }
            var debug = {
                innerTextLength: innerTextVal.length,
                textContentLength: textContentVal.length,
                innerTextPreview: innerTextVal.substring(0, 300),
                textContentPreview: textContentVal.replace(/\\s+/g, ' ').trim().substring(0, 300),
                readyState: document.readyState,
                scrollHeight: document.body.scrollHeight,
                viewportHeight: window.innerHeight,
                domElementCount: document.body.querySelectorAll('*').length,
                visibleSample: visibleCount,
                hiddenSample: hiddenCount,
                url: location.href,
                title: document.title
            };
            console.log('[getText body] debug:', JSON.stringify(debug));
            return JSON.stringify({text: text, length: text.length, debug: debug});
        })()
        """
    }

    // MARK: - Get Readable

    static func getReadable() -> String {
        """
        (function() {
            var candidateSelectors = [
                'article',
                '[role="main"]',
                'main',
                '.post-content',
                '.article-body',
                '.entry-content',
                '#content',
                '.content'
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
            var textContentVal = el.textContent || '';
            var text = innerTextVal.replace(/\\s+/g, ' ').trim().substring(0, 15000);
            var debugInfo = {
                matchedSelector: matchedSelector,
                elTag: el.tagName,
                elId: el.id || null,
                elClass: el.className ? el.className.split(' ').slice(0,3).join(' ') : null,
                innerTextLength: innerTextVal.length,
                textContentLength: textContentVal.length,
                finalTextLength: text.length,
                bodyInnerTextLength: (document.body.innerText || '').length,
                bodyTextContentLength: (document.body.textContent || '').length,
                scrollHeight: document.body.scrollHeight,
                viewportHeight: window.innerHeight
            };
            console.log('[getReadable] debug:', JSON.stringify(debugInfo));
            return JSON.stringify({title: title, text: text, length: text.length, source: el.tagName + (el.className ? '.' + el.className.split(' ')[0] : ''), debug: debugInfo});
        })()
        """
    }

    // MARK: - Scroll

    static func scroll(direction: BrowserActionInput.ScrollDirection, amount: Int, selector: String? = nil) -> String {
        let pixels = direction == .down ? amount : -amount
        let selectorJS: String
        if let selector {
            selectorJS = "'\(jsQuote(selector))'"
        } else {
            selectorJS = "null"
        }
        return """
        // Wait for document.body to be available (up to 5s)
        var waited = 0;
        while (!document.body && waited < 5000) {
            await new Promise(function(r) { setTimeout(r, 100); });
            waited += 100;
        }
        if (!document.body) return JSON.stringify({error: 'Timed out waiting for document.body (5s)'});

        var targetSelector = \(selectorJS);
        var target = null;
        var scrolledElement = 'window';

        if (targetSelector) {
            target = document.querySelector(targetSelector);
            if (!target) return JSON.stringify({error: 'Element not found: ' + targetSelector});
        } else {
            var beforeY = window.scrollY;
            window.scrollBy(0, \(pixels));
            if (window.scrollY !== beforeY) {
                var sh = document.documentElement.scrollHeight || document.body.scrollHeight;
                return JSON.stringify({scrolled: true, element: 'window', direction: '\(direction.rawValue)', amount: \(amount), scrollY: window.scrollY, scrollHeight: sh, viewportHeight: window.innerHeight});
            }
            // Window didn't scroll — find the largest scrollable element (max 10 levels deep)
            var best = null;
            var bestArea = 0;
            var others = [];
            function walk(el, depth) {
                if (depth > 10) return;
                var children = el.children;
                for (var i = 0; i < children.length; i++) {
                    var child = children[i];
                    var st = window.getComputedStyle(child);
                    var oy = st.overflowY;
                    if ((oy === 'auto' || oy === 'scroll') && child.scrollHeight > child.clientHeight + 5) {
                        var area = child.clientWidth * child.clientHeight;
                        var desc = child.tagName.toLowerCase();
                        if (child.id) desc += '#' + child.id;
                        else if (child.className && typeof child.className === 'string') {
                            var cls = child.className.trim().split(/\\s+/)[0];
                            if (cls) desc += '.' + cls;
                        }
                        var testId = child.getAttribute('data-testid');
                        if (testId) desc += '[data-testid=' + testId + ']';
                        if (area > bestArea) {
                            if (best) others.push(best.desc);
                            best = {el: child, area: area, desc: desc};
                            bestArea = area;
                        } else {
                            others.push(desc);
                        }
                    }
                    walk(child, depth + 1);
                }
            }
            walk(document.body, 0);
            if (best) {
                target = best.el;
                scrolledElement = best.desc;
            } else {
                document.documentElement.scrollTop += \(pixels);
                return JSON.stringify({scrolled: true, element: 'document.documentElement (forced)', direction: '\(direction.rawValue)', amount: \(amount), scrollY: document.documentElement.scrollTop, scrollHeight: document.documentElement.scrollHeight, viewportHeight: window.innerHeight});
            }
        }

        if (target) {
            target.scrollBy(0, \(pixels));
            var desc = targetSelector ? targetSelector : scrolledElement;
            var result = {scrolled: true, element: desc, direction: '\(direction.rawValue)', amount: \(amount), scrollTop: target.scrollTop, scrollHeight: target.scrollHeight, clientHeight: target.clientHeight};
            if (typeof others !== 'undefined' && others.length > 0) {
                result.otherScrollable = others;
                result.hint = 'There are ' + others.length + ' other scrollable element(s) on this page. Use selector parameter to scroll a specific one.';
            }
            return JSON.stringify(result);
        }
        """
    }

    static func scrollToElement(selector: String) -> String {
        """
        var waited = 0;
        while (!document.body && waited < 5000) {
            await new Promise(function(r) { setTimeout(r, 100); });
            waited += 100;
        }
        if (!document.body) return JSON.stringify({error: 'Timed out waiting for document.body (5s)'});
        var el = document.querySelector('\(jsQuote(selector))');
        if (!el) return JSON.stringify({error: 'Element not found: \(jsQuote(selector))'});
        el.scrollIntoView({behavior: 'smooth', block: 'center'});
        return JSON.stringify({scrolledTo: '\(jsQuote(selector))', tag: el.tagName});
        """
    }

    // MARK: - Hover

    static func hover(selector: String) -> String {
        """
        (function() {
            var el = document.querySelector('\(jsQuote(selector))');
            if (!el) return JSON.stringify({error: 'Element not found: \(jsQuote(selector))'});
            el.dispatchEvent(new MouseEvent('mouseenter', {bubbles: true}));
            el.dispatchEvent(new MouseEvent('mouseover', {bubbles: true}));
            return JSON.stringify({hovered: true, tag: el.tagName, text: (el.innerText || '').substring(0, 100)});
        })()
        """
    }

    // MARK: - Find Elements

    static func findElements(selector: String) -> String {
        """
        (function() {
            var els = document.querySelectorAll('\(jsQuote(selector))');
            var results = [];
            var limit = Math.min(els.length, 20);
            var scrollX = window.scrollX || window.pageXOffset || 0;
            var scrollY = window.scrollY || window.pageYOffset || 0;
            for (var i = 0; i < limit; i++) {
                var el = els[i];
                var rect = el.getBoundingClientRect();
                results.push({
                    index: i,
                    tag: el.tagName,
                    id: el.id || null,
                    className: el.className || null,
                    text: (el.innerText || '').substring(0, 80),
                    href: el.href || null,
                    rect: {x: Math.round(rect.x), y: Math.round(rect.y), width: Math.round(rect.width), height: Math.round(rect.height), pageX: Math.round(rect.x + scrollX), pageY: Math.round(rect.y + scrollY), visible: rect.width > 0 && rect.height > 0 && rect.top < window.innerHeight && rect.bottom > 0}
                });
            }
            return JSON.stringify({count: els.length, shown: limit, elements: results});
        })()
        """
    }

    // MARK: - Get Backbone

    static func getBackbone(maxDepth: Int) -> String {
        """
        (function() {
            var MAX_DEPTH = \(maxDepth);
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
                return Math.abs(a.left - b.left) <= MERGE_THRESHOLD &&
                       Math.abs(a.top - b.top) <= MERGE_THRESHOLD &&
                       Math.abs(a.right - b.right) <= MERGE_THRESHOLD &&
                       Math.abs(a.bottom - b.bottom) <= MERGE_THRESHOLD;
            }

            // Short selector: #id or #ancestor > tag:nth(n) — max 2 segments after anchor
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
                // Keep #id + last 2, or just last 2 segments
                if (path.length > 3 && path[0].charAt(0) === '#') {
                    path = [path[0]].concat(path.slice(-2));
                } else if (path.length > 2) {
                    path = path.slice(-2);
                }
                return path.join(' > ');
            }

            // Get text content
            function getText(el) {
                var t = (el.innerText || el.textContent || '').trim();
                return t.substring(0, 120);
            }

            // Check if a node has meaningful content
            function hasMeaningfulContent(el, rect) {
                if (rect.width < 12 || rect.height < 12) return false;
                var txt = getText(el);
                if (txt.length > 1) return true;
                if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT' || el.tagName === 'BUTTON') return true;
                if (el.tagName === 'IMG' && rect.width >= 24 && rect.height >= 24) return true;
                if (el.tagName === 'VIDEO' || el.tagName === 'CANVAS' || el.tagName === 'IFRAME') return true;
                if (el.tagName === 'A' && el.href && el.href.indexOf('javascript:') !== 0) return true;
                return false;
            }

            // Step 1: collect visible leaves
            var allEls = document.body.querySelectorAll('*');
            var leaves = [];
            for (var i = 0; i < allEls.length; i++) {
                var el = allEls[i];
                if (isVisible(el) && isLeaf(el)) {
                    var r = el.getBoundingClientRect();
                    if (hasMeaningfulContent(el, r)) leaves.push(el);
                }
            }

            // Step 2: bubble-merge each leaf upward
            var seen = new Set();
            var representatives = [];
            var mergedCount = 0;
            for (var i = 0; i < leaves.length; i++) {
                var rep = leaves[i];
                var cur = rep.parentElement;
                while (cur && cur !== document.body && cur !== document.documentElement) {
                    if (!isVisible(cur)) break;
                    if (rectsClose(rep.getBoundingClientRect(), cur.getBoundingClientRect())) {
                        rep = cur; mergedCount++;
                    } else { break; }
                    cur = cur.parentElement;
                }
                if (!seen.has(rep)) { seen.add(rep); representatives.push(rep); }
            }

            // Step 3: inject structural ancestors as grouping nodes
            // Walk DOM upward from each rep, collect ancestors that contain >=2 reps
            var ancestorMap = new Map();
            for (var i = 0; i < representatives.length; i++) {
                var cur = representatives[i].parentElement;
                var depth = 0;
                while (cur && cur !== document.body && cur !== document.documentElement && depth < 6) {
                    if (!ancestorMap.has(cur)) ancestorMap.set(cur, 0);
                    ancestorMap.set(cur, ancestorMap.get(cur) + 1);
                    cur = cur.parentElement;
                    depth++;
                }
            }
            // Add ancestors with >=2 descendant reps as structural group nodes
            var groupNodes = [];
            ancestorMap.forEach(function(count, el) {
                if (count >= 2 && !seen.has(el) && isVisible(el)) {
                    seen.add(el);
                    groupNodes.push(el);
                }
            });

            var allReps = representatives.concat(groupNodes);

            // Step 4: build node info
            var nodes = allReps.map(function(el) {
                var r = el.getBoundingClientRect();
                var info = {
                    el: el,
                    tag: el.tagName,
                    rect: {x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height)},
                    area: r.width * r.height,
                    children: [],
                    isGroup: groupNodes.indexOf(el) >= 0
                };
                if (el.id) info.id = el.id;
                var cn = el.className && typeof el.className === 'string' ? el.className.trim().split(/\\s+/).slice(0, 2).join(' ') : '';
                if (cn) info.className = cn;
                info.selector = shortSelector(el);
                if (!info.isGroup) {
                    var txt = getText(el);
                    if (txt) info.text = txt;
                }
                if (el.tagName === 'IMG') {
                    info.src = (el.src || '').substring(0, 120);
                    info.imgSize = el.naturalWidth + 'x' + el.naturalHeight;
                }
                if ((el.tagName === 'A') && el.href && el.href.indexOf('javascript:') !== 0) {
                    // Store only path portion for brevity
                    try {
                        var u = new URL(el.href);
                        info.href = u.pathname + (u.search ? u.search.substring(0, 40) : '');
                    } catch(e) { info.href = el.href.substring(0, 80); }
                }
                if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT') {
                    info.inputType = el.type || null;
                    // [T-ios-backbone-password-leak] GH#285. A password field's
                    // value went into the backbone verbatim (`val=hunter2`), so
                    // anything the user had typed — or that an env-var fill had
                    // placed there — reached the agent context and the model.
                    //
                    // Redacted at CAPTURE rather than at serialization so the
                    // plaintext never enters the intermediate node tree at all;
                    // the emptiness of the field is still reported, since
                    // whether a password box is filled is what the agent
                    // legitimately needs to know to drive a login form.
                    if (el.value) {
                        info.value = (el.type === 'password')
                            ? '[redacted]'
                            : el.value.substring(0, 60);
                    }
                    if (el.placeholder) info.placeholder = el.placeholder.substring(0, 60);
                }
                var role = el.getAttribute('role');
                if (role) info.role = role;
                return info;
            });

            // Step 5: build tree by real DOM containment
            nodes.sort(function(a, b) { return b.area - a.area; });

            function rectContains(outer, inner) {
                return outer.x <= inner.x + 2 && outer.y <= inner.y + 2 &&
                       (outer.x + outer.w) >= (inner.x + inner.w - 2) &&
                       (outer.y + outer.h) >= (inner.y + inner.h - 2);
            }
            // Also check DOM containment for accuracy
            function domContains(a, b) { return a.el.contains(b.el); }

            var roots = [];
            for (var i = 0; i < nodes.length; i++) {
                var node = nodes[i];
                var bestParent = null;
                var bestArea = Infinity;
                for (var j = 0; j < i; j++) {
                    if (nodes[j].el !== node.el && domContains(nodes[j], node) && nodes[j].area < bestArea) {
                        bestParent = nodes[j];
                        bestArea = nodes[j].area;
                    }
                }
                if (bestParent) { bestParent.children.push(node); }
                else { roots.push(node); }
            }

            // Step 6: prune empty group nodes (groups with 0-1 children get flattened)
            function prune(node) {
                for (var i = 0; i < node.children.length; i++) {
                    prune(node.children[i]);
                }
                // Flatten group nodes with only 1 child
                if (node.isGroup && node.children.length === 1 && !node.text) {
                    var child = node.children[0];
                    // Copy child's content up but keep group's broader rect
                    node.children = child.children;
                    if (child.text && !node.text) node.text = child.text;
                    if (child.href && !node.href) node.href = child.href;
                    if (child.src) { node.src = child.src; node.imgSize = child.imgSize; }
                    if (!node.id && child.id) node.id = child.id;
                    node.tag = child.tag;
                    node.selector = child.selector;
                    node.isGroup = false;
                }
                // Remove empty group nodes (0 children, no content)
                node.children = node.children.filter(function(c) {
                    if (c.isGroup && c.children.length === 0 && !c.text) return false;
                    return true;
                });
            }
            for (var i = 0; i < roots.length; i++) prune(roots[i]);
            roots = roots.filter(function(r) {
                if (r.isGroup && r.children.length === 0 && !r.text) return false;
                return true;
            });

            // Step 7: trim to maxDepth
            function trimDepth(node, depth) {
                if (depth >= MAX_DEPTH) { node.children = []; return; }
                for (var i = 0; i < node.children.length; i++) trimDepth(node.children[i], depth + 1);
            }
            for (var i = 0; i < roots.length; i++) trimDepth(roots[i], 1);

            // Step 8: serialize
            var totalNodes = 0, maxD = 0;
            var sX = window.scrollX || window.pageXOffset || 0;
            var sY = window.scrollY || window.pageYOffset || 0;
            function ser(node, depth) {
                totalNodes++;
                if (depth > maxD) maxD = depth;
                var o = {tag: node.tag};
                if (node.id) o.id = node.id;
                if (node.className) o.cls = node.className;
                o.sel = node.selector;
                if (node.role) o.role = node.role;
                if (node.text) o.text = node.text;
                if (node.href) o.href = node.href;
                if (node.src) { o.img = node.imgSize + ' ' + node.src; }
                if (node.inputType !== undefined) {
                    o.input = node.inputType + (node.value ? ' val=' + node.value : '') + (node.placeholder ? ' ph=' + node.placeholder : '');
                }
                o.rect = node.rect.x + ',' + node.rect.y + ' ' + node.rect.w + 'x' + node.rect.h;
                o.pageXY = (node.rect.x + sX) + ',' + (node.rect.y + sY);
                if (node.children.length > 0) {
                    o.children = node.children.map(function(c) { return ser(c, depth + 1); });
                }
                return o;
            }
            var tree = roots.map(function(r) { return ser(r, 1); });
            return JSON.stringify({backbone: tree, nodeCount: totalNodes, depth: maxD, merged: mergedCount});
        })()
        """
    }

    // MARK: - Scroll And Collect

    static func scrollAndCollect(direction: BrowserActionInput.ScrollDirection, amount: Int, scrollCount: Int, selector: String?, itemSelector: String?) -> String {
        let pixels = direction == .down ? amount : -amount
        let selectorJS: String
        if let selector {
            selectorJS = "'\(jsQuote(selector))'"
        } else {
            selectorJS = "null"
        }
        let itemSelectorJS: String
        if let itemSelector {
            itemSelectorJS = "'\(jsQuote(itemSelector))'"
        } else {
            itemSelectorJS = "null"
        }
        return """
        var SCROLL_COUNT = \(scrollCount);
        var PIXELS = \(pixels);
        var ITEM_CAP = 500;
        var TOTAL_CAP = 30000;
        var targetSelector = \(selectorJS);
        var itemSelector = \(itemSelectorJS);

        // Find scroll target
        var scrollTarget = null;
        var scrollFn = null;
        if (targetSelector) {
            scrollTarget = document.querySelector(targetSelector);
            if (!scrollTarget) return JSON.stringify({error: 'Scroll container not found: ' + targetSelector});
            scrollFn = function() { scrollTarget.scrollBy(0, PIXELS); };
        } else {
            // Try window first
            var beforeY = window.scrollY;
            window.scrollBy(0, PIXELS);
            if (window.scrollY !== beforeY) {
                // Window scrolls — reset and use it
                window.scrollBy(0, -PIXELS);
                scrollTarget = document.body;
                scrollFn = function() { window.scrollBy(0, PIXELS); };
            } else {
                // Find largest scrollable container
                var best = null;
                var bestArea = 0;
                function walkScroll(el, depth) {
                    if (depth > 10) return;
                    for (var i = 0; i < el.children.length; i++) {
                        var child = el.children[i];
                        var st = window.getComputedStyle(child);
                        var oy = st.overflowY;
                        if ((oy === 'auto' || oy === 'scroll') && child.scrollHeight > child.clientHeight + 5) {
                            var area = child.clientWidth * child.clientHeight;
                            if (area > bestArea) { best = child; bestArea = area; }
                        }
                        walkScroll(child, depth + 1);
                    }
                }
                walkScroll(document.body, 0);
                if (best) {
                    scrollTarget = best;
                    scrollFn = function() { best.scrollBy(0, PIXELS); };
                } else {
                    scrollTarget = document.documentElement;
                    scrollFn = function() { document.documentElement.scrollTop += PIXELS; };
                }
            }
        }

        // Determine item elements selector
        var effectiveItemSelector = itemSelector;
        if (!effectiveItemSelector) {
            var candidates = ['article', '[role="article"]', '[data-testid]', 'li'];
            for (var ci = 0; ci < candidates.length; ci++) {
                var found = scrollTarget.querySelectorAll(candidates[ci]);
                if (found.length >= 3) { effectiveItemSelector = candidates[ci]; break; }
            }
            if (!effectiveItemSelector) {
                // Heuristic: most common direct-child tag
                var tagCounts = {};
                var children = scrollTarget.children;
                for (var i = 0; i < children.length; i++) {
                    var t = children[i].tagName;
                    tagCounts[t] = (tagCounts[t] || 0) + 1;
                }
                var bestTag = null, bestCount = 0;
                for (var t in tagCounts) {
                    if (tagCounts[t] > bestCount) { bestTag = t; bestCount = tagCounts[t]; }
                }
                if (bestTag && bestCount >= 3) {
                    effectiveItemSelector = ':scope > ' + bestTag.toLowerCase();
                } else {
                    effectiveItemSelector = ':scope > *';
                }
            }
        }

        // Dedup set keyed on first 80 chars of trimmed text
        var seen = new Set();
        var items = [];
        var totalSeen = 0;

        function extractItems() {
            var els;
            try { els = scrollTarget.querySelectorAll(effectiveItemSelector); } catch(e) { els = []; }
            var newCount = 0;
            for (var i = 0; i < els.length; i++) {
                var txt = (els[i].innerText || '').trim();
                if (!txt) continue;
                totalSeen++;
                var key = txt.substring(0, 80);
                if (seen.has(key)) continue;
                seen.add(key);
                items.push(txt.substring(0, ITEM_CAP));
                newCount++;
            }
            return newCount;
        }

        // Collect initial content
        extractItems();

        // Scroll loop
        var scrollSteps = 0;
        for (var step = 0; step < SCROLL_COUNT; step++) {
            // Set up MutationObserver
            var mutated = false;
            var mutationResolve = null;
            var mutationPromise = new Promise(function(resolve) { mutationResolve = resolve; });
            var observer = new MutationObserver(function() { mutated = true; mutationResolve(); });
            observer.observe(scrollTarget, { childList: true, subtree: true });

            // Scroll
            scrollFn();
            scrollSteps++;

            // Wait up to 2s for first mutation
            var timeoutId = setTimeout(function() { mutationResolve(); }, 2000);
            await mutationPromise;
            clearTimeout(timeoutId);

            if (mutated) {
                // Wait for settle: no mutations for 300ms, up to 1s total
                var settleStart = Date.now();
                while (Date.now() - settleStart < 1000) {
                    mutated = false;
                    await new Promise(function(r) { setTimeout(r, 300); });
                    if (!mutated) break;
                }
            }

            observer.disconnect();

            // Small delay for rendering
            await new Promise(function(r) { setTimeout(r, 100); });

            // Extract new items
            var newItems = extractItems();

            // Early termination if no new items
            if (newItems === 0) break;

            // Check total output cap
            var totalLen = 0;
            for (var ci = 0; ci < items.length; ci++) totalLen += items[ci].length;
            if (totalLen >= TOTAL_CAP) break;
        }

        // Trim to total cap
        var finalItems = [];
        var runningLen = 0;
        for (var i = 0; i < items.length; i++) {
            if (runningLen + items[i].length > TOTAL_CAP) {
                finalItems.push(items[i].substring(0, TOTAL_CAP - runningLen));
                break;
            }
            finalItems.push(items[i]);
            runningLen += items[i].length;
        }

        var scrollPos = scrollTarget === document.body || scrollTarget === document.documentElement
            ? window.scrollY : scrollTarget.scrollTop;

        return JSON.stringify({
            itemsCollected: finalItems.length,
            totalItemsSeen: totalSeen,
            scrollSteps: scrollSteps,
            scrollPosition: scrollPos,
            itemSelector: effectiveItemSelector,
            items: finalItems
        });
        """
    }

    // MARK: - Get Page Info

    static func getPageInfo() -> String {
        """
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
        """
    }
}
