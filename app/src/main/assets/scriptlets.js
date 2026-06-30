/**
 * RethinkDNS Scriptlet Library
 *
 * Implements the most commonly used AdGuard/uBlock scriptlets for anti-adblock bypass.
 * Each scriptlet is exposed via window.rethinkScriptlets namespace and invoked by
 * ScriptletFilter.kt which wraps individual calls into injected <script> tags.
 *
 * Scriptlets implemented:
 *   abort-on-property-read   (aopr)  — throw when script reads a property
 *   abort-on-property-write  (aopw)  — throw when script writes a property
 *   set-constant             (sc)    — override a property with a constant value
 *   remove-attr                      — remove an HTML attribute on matching elements
 *   remove-class                     — remove a CSS class on matching elements
 *   no-fetch-if                      — block fetch() requests matching a pattern
 *   no-xhr-if                        — block XMLHttpRequest matching a pattern
 *   json-prune                       — delete keys from JSON responses
 */
(function (global) {
  'use strict';

  var lib = {};

  // ── Helpers ────────────────────────────────────────────────────────────────

  function getPropertyChain(root, chain) {
    var parts = chain.split('.');
    var obj = root;
    for (var i = 0; i < parts.length - 1; i++) {
      if (obj == null) return null;
      obj = obj[parts[i]];
    }
    return { obj: obj, prop: parts[parts.length - 1] };
  }

  function matchesPattern(str, pattern) {
    if (!pattern) return true;
    if (pattern.startsWith('/') && pattern.endsWith('/')) {
      try { return new RegExp(pattern.slice(1, -1)).test(str); } catch (e) { return false; }
    }
    return str.indexOf(pattern) !== -1;
  }

  // ── 1. abort-on-property-read ──────────────────────────────────────────────
  /**
   * Throws a ReferenceError when a script tries to READ the given property.
   * Most effective against: adsbygoogle, googletag, fbq, etc.
   *
   * @param {string} prop  Dot-path property, e.g. "adsbygoogle" or "google.ima"
   */
  lib['abort-on-property-read'] = lib['aopr'] = function (prop) {
    if (!prop) return;
    var chain = getPropertyChain(global, prop);
    if (!chain || !chain.obj) return;
    try {
      Object.defineProperty(chain.obj, chain.prop, {
        get: function () {
          throw new ReferenceError('RethinkDNS: aborted read of ' + prop);
        },
        configurable: true
      });
    } catch (e) {}
  };

  // ── 2. abort-on-property-write ─────────────────────────────────────────────
  /**
   * Throws a ReferenceError when a script tries to WRITE the given property.
   *
   * @param {string} prop  Dot-path property
   */
  lib['abort-on-property-write'] = lib['aopw'] = function (prop) {
    if (!prop) return;
    var chain = getPropertyChain(global, prop);
    if (!chain || !chain.obj) return;
    try {
      Object.defineProperty(chain.obj, chain.prop, {
        set: function () {
          throw new ReferenceError('RethinkDNS: aborted write of ' + prop);
        },
        configurable: true
      });
    } catch (e) {}
  };

  // ── 3. set-constant ────────────────────────────────────────────────────────
  /**
   * Forces a property to always return a constant value regardless of what
   * scripts try to assign to it.
   *
   * @param {string} prop   Dot-path property
   * @param {string} value  One of: true, false, null, undefined, emptyObj, emptyArr,
   *                        noopFunc, trueFunc, falseFunc, or a numeric string
   */
  lib['set-constant'] = lib['sc'] = function (prop, value) {
    if (!prop) return;
    var resolved;
    switch (value) {
      case 'true':     resolved = true;          break;
      case 'false':    resolved = false;         break;
      case 'null':     resolved = null;          break;
      case 'undefined': resolved = undefined;   break;
      case 'emptyObj': resolved = {};            break;
      case 'emptyArr': resolved = [];            break;
      case 'noopFunc': resolved = function () {}; break;
      case 'trueFunc': resolved = function () { return true; }; break;
      case 'falseFunc': resolved = function () { return false; }; break;
      default:
        var n = Number(value);
        resolved = isNaN(n) ? value : n;
    }
    var chain = getPropertyChain(global, prop);
    if (!chain || !chain.obj) return;
    try {
      Object.defineProperty(chain.obj, chain.prop, {
        get: function () { return resolved; },
        set: function () {},
        configurable: true
      });
    } catch (e) {}
  };

  // ── 4. remove-attr ─────────────────────────────────────────────────────────
  /**
   * Removes an HTML attribute from elements matching a CSS selector.
   *
   * @param {string} attr      Attribute name to remove
   * @param {string} selector  CSS selector (default: *[attr])
   */
  lib['remove-attr'] = function (attr, selector) {
    if (!attr) return;
    var sel = selector || ('[' + attr + ']');
    function run() {
      try {
        document.querySelectorAll(sel).forEach(function (el) {
          el.removeAttribute(attr);
        });
      } catch (e) {}
    }
    run();
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', run);
    }
    try {
      new MutationObserver(run).observe(document.documentElement, { childList: true, subtree: true });
    } catch (e) {}
  };

  // ── 5. remove-class ────────────────────────────────────────────────────────
  /**
   * Removes a CSS class from elements matching a selector.
   *
   * @param {string} className  Class name to remove
   * @param {string} selector   CSS selector (default: .className)
   */
  lib['remove-class'] = function (className, selector) {
    if (!className) return;
    var sel = selector || ('.' + className);
    function run() {
      try {
        document.querySelectorAll(sel).forEach(function (el) {
          el.classList.remove(className);
        });
      } catch (e) {}
    }
    run();
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', run);
    }
    try {
      new MutationObserver(run).observe(document.documentElement, { childList: true, subtree: true });
    } catch (e) {}
  };

  // ── 6. no-fetch-if ─────────────────────────────────────────────────────────
  /**
   * Blocks fetch() requests whose URL matches a pattern.
   * Returns an empty 200 response for blocked requests.
   *
   * @param {string} pattern  String or /regex/ to match against the request URL
   */
  lib['no-fetch-if'] = function (pattern) {
    if (typeof global.fetch !== 'function') return;
    var origFetch = global.fetch;
    global.fetch = function (input, init) {
      var url = (typeof input === 'string') ? input : (input && input.url) || '';
      if (matchesPattern(url, pattern)) {
        return Promise.resolve(new Response('', { status: 200 }));
      }
      return origFetch.apply(this, arguments);
    };
  };

  // ── 7. no-xhr-if ───────────────────────────────────────────────────────────
  /**
   * Blocks XMLHttpRequest requests whose URL matches a pattern.
   * The request silently completes with an empty response body.
   *
   * @param {string} pattern  String or /regex/ to match against the request URL
   */
  lib['no-xhr-if'] = function (pattern) {
    if (typeof global.XMLHttpRequest !== 'function') return;
    var OrigXHR = global.XMLHttpRequest;
    function PatchedXHR() {
      this._blocked = false;
      this._xhr = new OrigXHR();
      // Proxy all standard events
      var self = this;
      ['onload','onerror','onabort','onprogress','onreadystatechange'].forEach(function (ev) {
        Object.defineProperty(self, ev, {
          set: function (fn) { self._xhr[ev] = fn; },
          get: function () { return self._xhr[ev]; }
        });
      });
    }
    PatchedXHR.prototype.open = function (method, url) {
      if (matchesPattern(url, pattern)) {
        this._blocked = true;
        return;
      }
      this._xhr.open.apply(this._xhr, arguments);
    };
    PatchedXHR.prototype.send = function () {
      if (this._blocked) {
        var self = this;
        setTimeout(function () {
          Object.defineProperty(self, 'readyState', { get: function () { return 4; } });
          Object.defineProperty(self, 'status', { get: function () { return 200; } });
          Object.defineProperty(self, 'responseText', { get: function () { return ''; } });
          if (typeof self.onreadystatechange === 'function') self.onreadystatechange();
          if (typeof self.onload === 'function') self.onload();
        }, 0);
        return;
      }
      this._xhr.send.apply(this._xhr, arguments);
    };
    PatchedXHR.prototype.setRequestHeader = function () {
      if (!this._blocked) this._xhr.setRequestHeader.apply(this._xhr, arguments);
    };
    PatchedXHR.prototype.abort = function () {
      if (!this._blocked) this._xhr.abort();
    };
    global.XMLHttpRequest = PatchedXHR;
  };

  // ── 8. json-prune ──────────────────────────────────────────────────────────
  /**
   * Removes specified keys from JSON.parse results.
   * Useful for stripping ad-related keys from API responses.
   *
   * @param {string} propsToRemove  Space-separated list of dot-path keys to delete
   * @param {string} requiredProps  Space-separated list of keys that must exist (optional guard)
   */
  lib['json-prune'] = function (propsToRemove, requiredProps) {
    if (!propsToRemove) return;
    var removeList = propsToRemove.split(/\s+/);
    var requireList = requiredProps ? requiredProps.split(/\s+/) : [];
    var origParse = JSON.parse;
    JSON.parse = function () {
      var result = origParse.apply(this, arguments);
      if (result === null || typeof result !== 'object') return result;
      // Check required props guard
      if (requireList.length > 0) {
        var hasAll = requireList.every(function (p) {
          var c = getPropertyChain(result, p);
          return c && c.obj && c.prop in c.obj;
        });
        if (!hasAll) return result;
      }
      // Prune listed props
      removeList.forEach(function (p) {
        var c = getPropertyChain(result, p);
        if (c && c.obj) { try { delete c.obj[c.prop]; } catch (e) {} }
      });
      return result;
    };
  };

  // ── Expose namespace ───────────────────────────────────────────────────────
  global.rethinkScriptlets = lib;

}(typeof globalThis !== 'undefined' ? globalThis : typeof self !== 'undefined' ? self : this));
