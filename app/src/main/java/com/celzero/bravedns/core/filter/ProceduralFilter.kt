/*
 * Copyright 2026 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.core.filter

import java.util.Locale

/**
 * ProceduralFilter implements AdGuard/uBlock procedural cosmetic filter support.
 *
 * Standard cosmetic filters (##) use plain CSS which the browser applies natively.
 * Procedural filters (#?#) use pseudo-operators that CSS cannot express alone —
 * they are evaluated by injecting a small JavaScript snippet that walks the DOM
 * and hides matching elements.
 *
 * Supported operators (priority order matches AdGuard spec):
 *   :has(selector)           — hide element if it contains a descendant matching selector
 *   :has-text(text)          — hide element if its text content contains the string
 *   :matches-css(prop: val)  — hide element if computed style matches
 *   :upward(n|selector)      — hide the n-th ancestor, or nearest ancestor matching selector
 *   :xpath(expr)             — hide elements matching an XPath expression
 *   :nth-ancestor(n)         — alias for :upward(n) with numeric argument
 *   :remove()                — remove element from DOM entirely (not just hide)
 *
 * Syntax examples:
 *   example.com#?#.banner:has(img[src*="ads"])
 *   example.com#?#div:has-text(Sponsored)
 *   example.com#?#.widget:matches-css(display: block)
 *   example.com#?#p:upward(2)
 *   example.com#?#:xpath(//div[@data-ad-slot])
 *
 * Rules with no domain part apply globally to all domains.
 */
object ProceduralFilter {

    private const val TAG = "ProceduralFilter"

    data class ProceduralRule(
        val rawSelector: String,          // full selector including operator, e.g. ".banner:has(img)"
        val allowedDomains: Set<String>?,
        val excludedDomains: Set<String>?
    )

    @Volatile
    private var isInitialized = false
    private val rules = ArrayList<ProceduralRule>()

    // LRU cache: domain → JS snippet (or null if no rules match)
    private val jsCache = object : LinkedHashMap<String, String?>(200, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, String?>?): Boolean = size > 200
    }

    @Synchronized
    fun clear() {
        rules.clear()
        synchronized(jsCache) { jsCache.clear() }
        isInitialized = false
    }

    fun onRulesReloaded() {
        clear()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns a JS snippet to inject into [domain]'s HTML response, or null if no rules match.
     * The snippet runs immediately (inline) and also on DOMContentLoaded for dynamic pages.
     */
    fun getScriptForDomain(domain: String): String? {
        if (!FilterEngine.isLoaded) return null

        val host = domain.trim().lowercase(Locale.US)
        synchronized(jsCache) {
            if (jsCache.containsKey(host)) return jsCache[host]
        }

        ensureInitialized()

        val matching = rules.filter { rule ->
            domainMatches(host, rule.allowedDomains, rule.excludedDomains)
        }

        val result = if (matching.isEmpty()) null else buildJs(matching)

        synchronized(jsCache) { jsCache[host] = result }
        return result
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun ensureInitialized() {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            rules.clear()
            val rawRules: List<String>
            synchronized(FilterEngine) {
                rawRules = FilterEngine.proceduralRules.toList()
            }
            for (raw in rawRules) {
                parseProceduralRule(raw)?.let { rules.add(it) }
            }
            isInitialized = true
        }
    }

    /**
     * Parses a raw #?# rule line into a ProceduralRule.
     *
     * Format: [domains]#?#<selector>
     * e.g.:   example.com,~other.com#?#.ad:has(img)
     */
    internal fun parseProceduralRule(rawLine: String): ProceduralRule? {
        val sepIdx = rawLine.indexOf("#?#")
        if (sepIdx == -1) return null

        val domainPart = rawLine.substring(0, sepIdx).trim()
        val selectorPart = rawLine.substring(sepIdx + 3).trim()
        if (selectorPart.isEmpty()) return null

        var allowedDomains: MutableSet<String>? = null
        var excludedDomains: MutableSet<String>? = null

        if (domainPart.isNotEmpty()) {
            for (dom in domainPart.split(",")) {
                val d = dom.trim().lowercase(Locale.US)
                if (d.isEmpty()) continue
                if (d.startsWith("~")) {
                    if (excludedDomains == null) excludedDomains = HashSet()
                    excludedDomains!!.add(d.substring(1))
                } else {
                    if (allowedDomains == null) allowedDomains = HashSet()
                    allowedDomains!!.add(d)
                }
            }
        }

        return ProceduralRule(selectorPart, allowedDomains, excludedDomains)
    }

    /**
     * Builds a self-executing JS snippet that hides all elements matched by the given rules.
     *
     * The snippet:
     * 1. Runs immediately (catches elements already in the DOM)
     * 2. Runs again on DOMContentLoaded (catches late-rendered elements)
     * 3. Sets up a MutationObserver for fully dynamic pages
     *
     * Each rule's selector is translated into a JS call that handles the
     * procedural operator (:has, :has-text, :upward, :matches-css, :xpath, :remove).
     */
    internal fun buildJs(rules: List<ProceduralRule>): String {
        if (rules.isEmpty()) return ""

        val selectorsJson = rules.joinToString(",\n  ") { rule ->
            "\"${rule.rawSelector.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        }

        return """
(function() {
  'use strict';
  var SELECTORS = [
  $selectorsJson
  ];

  function applyHideRule(selector) {
    try {
      // :xpath operator
      if (selector.indexOf(':xpath(') !== -1) {
        var xpathMatch = selector.match(/:xpath\((.+)\)$/);
        if (xpathMatch) {
          var result = document.evaluate(xpathMatch[1], document, null,
            XPathResult.UNORDERED_NODE_SNAPSHOT_TYPE, null);
          for (var i = 0; i < result.snapshotLength; i++) {
            hideElement(result.snapshotItem(i), selector);
          }
          return;
        }
      }

      // :upward(n) or :upward(selector) or :nth-ancestor(n)
      var upwardMatch = selector.match(/^(.+):(?:upward|nth-ancestor)\((.+)\)$/);
      if (upwardMatch) {
        var baseNodes = querySelectorSafe(upwardMatch[1]);
        var arg = upwardMatch[2].trim();
        baseNodes.forEach(function(el) {
          var target = resolveUpward(el, arg);
          if (target) hideElement(target, selector);
        });
        return;
      }

      // :has-text(text)
      var hasTextMatch = selector.match(/^(.+):has-text\((.+)\)$/);
      if (hasTextMatch) {
        var baseNodes = querySelectorSafe(hasTextMatch[1]);
        var text = hasTextMatch[2];
        baseNodes.forEach(function(el) {
          if (el.textContent && el.textContent.indexOf(text) !== -1) {
            hideElement(el, selector);
          }
        });
        return;
      }

      // :matches-css(prop: val)
      var matchesCssMatch = selector.match(/^(.+):matches-css\((.+)\)$/);
      if (matchesCssMatch) {
        var baseNodes = querySelectorSafe(matchesCssMatch[1]);
        var cssParts = matchesCssMatch[2].split(':');
        if (cssParts.length >= 2) {
          var prop = cssParts[0].trim();
          var val = cssParts.slice(1).join(':').trim();
          baseNodes.forEach(function(el) {
            var computed = window.getComputedStyle(el);
            if (computed && computed.getPropertyValue(prop).trim() === val) {
              hideElement(el, selector);
            }
          });
        }
        return;
      }

      // :remove() — fully remove from DOM
      var removeMatch = selector.match(/^(.+):remove\(\)$/);
      if (removeMatch) {
        var baseNodes = querySelectorSafe(removeMatch[1]);
        baseNodes.forEach(function(el) {
          if (el.parentNode) el.parentNode.removeChild(el);
        });
        return;
      }

      // :has(selector) — use native CSS :has() if supported, otherwise polyfill
      if (selector.indexOf(':has(') !== -1) {
        try {
          // Try native CSS :has() first (Chrome 105+, Firefox 121+)
          var nodes = document.querySelectorAll(selector);
          nodes.forEach(function(el) { hideElement(el, selector); });
        } catch (e) {
          // Polyfill: parse manually
          var hasMatch = selector.match(/^(.+):has\((.+)\)$/);
          if (hasMatch) {
            var baseNodes = querySelectorSafe(hasMatch[1]);
            baseNodes.forEach(function(el) {
              if (el.querySelector(hasMatch[2])) hideElement(el, selector);
            });
          }
        }
        return;
      }

      // Plain CSS selector (fallback)
      var nodes = querySelectorSafe(selector);
      nodes.forEach(function(el) { hideElement(el, selector); });

    } catch (e) {
      // Silently ignore errors to avoid breaking pages
    }
  }

  function hideElement(el, selector) {
    if (el && el.style) {
      el.style.setProperty('display', 'none', 'important');
    }
  }

  function querySelectorSafe(sel) {
    try {
      return Array.from(document.querySelectorAll(sel));
    } catch (e) {
      return [];
    }
  }

  function resolveUpward(el, arg) {
    var n = parseInt(arg, 10);
    if (!isNaN(n)) {
      var current = el;
      for (var i = 0; i < n; i++) {
        if (!current || !current.parentElement) return null;
        current = current.parentElement;
      }
      return current;
    }
    // selector-based upward
    return el.closest(arg) || null;
  }

  function runAll() {
    SELECTORS.forEach(applyHideRule);
  }

  // Run immediately
  runAll();

  // Run again after DOM is ready
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', runAll);
  }

  // MutationObserver for dynamically injected content
  try {
    var observer = new MutationObserver(function(mutations) {
      var hasNewNodes = mutations.some(function(m) { return m.addedNodes.length > 0; });
      if (hasNewNodes) runAll();
    });
    observer.observe(document.documentElement, { childList: true, subtree: true });
  } catch (e) {}

})();
""".trimIndent()
    }

    private fun domainMatches(host: String, allowed: Set<String>?, excluded: Set<String>?): Boolean {
        if (excluded != null && excluded.isNotEmpty()) {
            if (isDomainOrSuffixMatch(host, excluded)) return false
        }
        if (allowed != null && allowed.isNotEmpty()) {
            return isDomainOrSuffixMatch(host, allowed)
        }
        return true
    }

    private fun isDomainOrSuffixMatch(host: String, domains: Set<String>): Boolean {
        if (domains.contains(host)) return true
        var idx = host.indexOf('.')
        while (idx != -1) {
            if (domains.contains(host.substring(idx + 1))) return true
            idx = host.indexOf('.', idx + 1)
        }
        return false
    }
}
