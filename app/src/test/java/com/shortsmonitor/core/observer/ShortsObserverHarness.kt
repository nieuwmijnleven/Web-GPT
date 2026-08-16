package com.shortsmonitor.core.observer

import org.json.JSONArray
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable

/**
 * JavaScript 관찰기 고정 HTML 테스트 하네스 (P단계).
 *
 * 유튜브 실페이지에 의존하지 않고, 고정 HTML 문자열을 파싱한 최소 가짜 DOM에서
 * [ShortsObserverScript]를 실행해 관찰기가 네이티브로 보내는 메시지를 검증한다.
 *
 * 가짜 환경이 제공하는 것:
 * - 최소 DOM(요소·쿼리·속성·텍스트)과 단일 복합 선택자 매칭
 * - MutationObserver·setTimeout/setInterval(가상 시간 플러시)
 * - location/history/performance/document/window 스텁
 * - `window.shortsMonitorBridge.postMessage` 캡처
 */
object ShortsObserverHarness {

    /**
     * 가짜 브라우저 환경 JS.
     * 테스트가 고정 HTML을 주입하고(`__setItems`/`__rebuildFeed`/`__setActive`),
     * 가상 시간을 진행해(`__flushTimers`) 관찰기 메시지를 캡처한다.
     */
    private val HARNESS_JS = """
        // ===== 가상 타이머 =====
        var __now = 0;
        var __timers = [];
        var __timerId = 1;

        function __setTimeout(fn, ms) {
          var id = __timerId++;
          __timers.push({ id: id, fn: fn, at: __now + (ms || 0), interval: false, delay: ms || 0 });
          return id;
        }
        function __clearTimeout(id) {
          for (var i = 0; i < __timers.length; i++) {
            if (__timers[i].id === id) { __timers.splice(i, 1); return; }
          }
        }
        function __setInterval(fn, ms) {
          var id = __timerId++;
          __timers.push({ id: id, fn: fn, at: __now + (ms || 0), interval: true, delay: ms || 0 });
          return id;
        }
        function __clearInterval(id) { __clearTimeout(id); }

        // 관찰기 스크립트는 표준 이름(setTimeout/setInterval)을 사용한다.
        var setTimeout = __setTimeout;
        var setInterval = __setInterval;
        var clearTimeout = __clearTimeout;
        var clearInterval = __clearInterval;

        // 가상 시간을 ms만큼 진행하고 만료된 타이머를 순서대로 실행한다.
        function __flushTimers(ms) {
          __now += (ms || 0);
          var guard = 0;
          while (true) {
            var due = null;
            var dueIndex = -1;
            for (var i = 0; i < __timers.length; i++) {
              if (__timers[i].at <= __now) { due = __timers[i]; dueIndex = i; break; }
            }
            if (!due) break;
            __timers.splice(dueIndex, 1);
            due.fn();
            if (due.interval) { due.at = __now + due.delay; __timers.push(due); }
            guard++;
            if (guard > 500) break;
          }
        }

        // ===== 최소 DOM =====
        function makeEl(tag, attrs, children) {
          var el = {
            tagName: tag.toUpperCase(),
            attrs: attrs || {},
            children: children || [],
            parent: null,
            parentElement: null,
            text: '',
            getAttribute: function (n) { return (n in this.attrs) ? this.attrs[n] : null; },
            hasAttribute: function (n) { return (n in this.attrs); },
            contains: function (other) {
              var cur = other;
              while (cur) { if (cur === this) return true; cur = cur.parent; }
              return false;
            },
            querySelectorAll: function (sel) {
              var out = [];
              function walk(node) {
                for (var i = 0; i < node.children.length; i++) {
                  var c = node.children[i];
                  if (matchSelector(c, sel)) out.push(c);
                  walk(c);
                }
              }
              walk(this);
              return out;
            },
            querySelector: function (sel) {
              var list = this.querySelectorAll(sel);
              return list.length > 0 ? list[0] : null;
            }
          };
          // 실제 DOM 앵커처럼 href 속성을 프로퍼티로도 노출한다.
          if (attrs && attrs['href'] !== undefined) { el.href = attrs['href']; }
          return el;
        }

        // 단일 복합 선택자 지원: tag / #id / .class / [attr] / [attr=v] / [attr*=v]
        function matchSelector(el, sel) {
          var tag = null;
          var id = null;
          var classes = [];
          var attrs = [];
          var re = /([a-zA-Z][\w-]*)|(#[\w-]+)|(\.[\w-]+)|(\[[^\]]+\])/g;
          var m;
          while ((m = re.exec(sel)) !== null) {
            if (m[1]) tag = m[1].toLowerCase();
            else if (m[2]) id = m[2].slice(1);
            else if (m[3]) classes.push(m[3].slice(1));
            else if (m[4]) {
              var inner = m[4].slice(1, -1);
              var eq = inner.indexOf('=');
              if (eq >= 0) {
                var name = inner.slice(0, eq).trim();
                // [attr*="v"] 형태는 이름 끝의 '*'를 제거한다.
                if (name.charAt(name.length - 1) === '*') { name = name.slice(0, -1); }
                var value = inner.slice(eq + 1).trim().replace(/^"|"$/g, '');
                if (inner.indexOf('*=') >= 0) attrs.push({ name: name, op: 'contains', value: value });
                else attrs.push({ name: name, op: 'eq', value: value });
              } else {
                attrs.push({ name: inner.trim(), op: 'exists', value: null });
              }
            }
          }
          if (tag && el.tagName.toLowerCase() !== tag) return false;
          if (id && el.attrs['id'] !== id) return false;
          for (var i = 0; i < classes.length; i++) {
            var cls = (el.attrs['class'] || '').split(/\s+/);
            if (cls.indexOf(classes[i]) < 0) return false;
          }
          for (var j = 0; j < attrs.length; j++) {
            var at = attrs[j];
            var val = el.attrs[at.name];
            if (at.op === 'exists') { if (val === undefined) return false; }
            else if (at.op === 'eq') { if (val !== at.value) return false; }
            else if (at.op === 'contains') { if (!val || val.indexOf(at.value) < 0) return false; }
          }
          return true;
        }

        function parseAttrs(s) {
          var attrs = {};
          var re = /([\w:-]+)(?:=(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+)))?/g;
          var m;
          while ((m = re.exec(s)) !== null) {
            var value = m[2] !== undefined ? m[2] : (m[3] !== undefined ? m[3] : (m[4] !== undefined ? m[4] : ''));
            attrs[m[1]] = value;
          }
          return attrs;
        }

        function computeText(node) {
          var total = node.text || '';
          for (var i = 0; i < node.children.length; i++) {
            total += computeText(node.children[i]);
          }
          node.textContent = total;
          return total;
        }

        var VOID_TAGS = { img: 1, br: 1, meta: 1, input: 1, link: 1, hr: 1 };

        function parseFragment(html) {
          var root = makeEl('#fragment', {}, []);
          var stack = [root];
          var re = /<(\/?)([a-zA-Z][\w-]*)((?:[^>"']|"[^"]*"|'[^']*')*?)(\/?)>/g;
          var last = 0;
          var m;
          while ((m = re.exec(html)) !== null) {
            var text = html.slice(last, m.index);
            if (text) { stack[stack.length - 1].text += text; }
            last = re.lastIndex;
            var closing = m[1] === '/';
            var tag = m[2];
            var selfClose = m[4] === '/';
            if (closing) {
              if (stack.length > 1) stack.pop();
              continue;
            }
            var el = makeEl(tag, parseAttrs(m[3]), []);
            var top = stack[stack.length - 1];
            top.children.push(el);
            el.parent = top;
            el.parentElement = top;
            if (!selfClose && !VOID_TAGS[tag.toLowerCase()]) stack.push(el);
          }
          var tail = html.slice(last);
          if (tail) { stack[stack.length - 1].text += tail; }
          computeText(root);
          return root;
        }

        // ===== 문서 =====
        var __docBody = makeEl('#document', {}, []);
        var __feed = null;

        var document = {
          title: 'Shorts',
          activeElement: null,
          querySelector: function (sel) { return this.querySelectorAll(sel)[0] || null; },
          querySelectorAll: function (sel) {
            var out = [];
            function walk(node) {
              for (var i = 0; i < node.children.length; i++) {
                var c = node.children[i];
                if (matchSelector(c, sel)) out.push(c);
                walk(c);
              }
            }
            walk(__docBody);
            return out;
          }
        };

        // ===== MutationObserver =====
        var __observerCallback = null;
        function MutationObserver(callback) {
          this._cb = callback;
        }
        MutationObserver.prototype.observe = function (node, config) {
          __observerCallback = this._cb;
        };
        MutationObserver.prototype.disconnect = function () {
          if (__observerCallback === this._cb) { __observerCallback = null; }
        };

        // 관찰기 콜백을 실제 DOM 변경처럼 발생시킨다.
        function __notifyMutations(records) {
          if (__observerCallback) {
            try { __observerCallback(records); } catch (e) { if (window.console) window.console.error(e); }
          }
        }

        // ===== 테스트 주입 API =====
        // 피드 항목을 교체한다. 실제 DOM 변경처럼 MutationObserver 콜백을 발생시킨다.
        function __setItems(itemsHtml) {
          if (!__feed) {
            __feed = makeEl('ytm-shorts', {}, []);
            __feed.parent = __docBody;
            __feed.parentElement = __docBody;
            __docBody.children = [__feed];
          }
          var frag = parseFragment(itemsHtml);
          __feed.children = frag.children;
          for (var i = 0; i < __feed.children.length; i++) {
            __feed.children[i].parent = __feed;
            __feed.children[i].parentElement = __feed;
          }
          __notifyMutations([{ type: 'childList' }]);
        }

        // 피드 컨테이너 자체를 새 요소로 교체한다 (컨테이너 전체 재생성).
        function __rebuildFeed(itemsHtml) {
          var frag = parseFragment(itemsHtml);
          var newFeed = makeEl('ytm-shorts', {}, []);
          newFeed.children = frag.children;
          for (var i = 0; i < newFeed.children.length; i++) {
            newFeed.children[i].parent = newFeed;
            newFeed.children[i].parentElement = newFeed;
          }
          newFeed.parent = __docBody;
          newFeed.parentElement = __docBody;
          __feed = newFeed;
          __docBody.children = [newFeed];
          __notifyMutations([{ type: 'childList' }]);
        }

        // index 번째 항목에 is-active를 설정하고 나머지에서는 제거한다.
        function __setActive(index) {
          var items = __docBody.querySelectorAll('ytm-shorts-video-renderer');
          for (var i = 0; i < items.length; i++) {
            if (i === index) items[i].attrs['is-active'] = '';
            else delete items[i].attrs['is-active'];
          }
          __notifyMutations([{ type: 'attributes', attributeName: 'is-active' }]);
        }

        // ===== 브라우저 스텁 =====
        var location = { href: 'https://m.youtube.com/shorts' };
        var history = { pushState: function () {}, replaceState: function () {} };
        var performance = {
          getEntriesByType: function (t) { return [{ type: 'navigate' }]; }
        };
        var window = {
          addEventListener: function () {},
          console: { error: function () {}, log: function () {}, warn: function () {} },
          shortsMonitorBridge: {
            postMessage: function (json) {
              __messages.push(JSON.parse(json));
            }
          }
        };

        // ===== 메시지 캡처 =====
        var __messages = [];
        function __clearMessages() { __messages = []; }

        // ===== 네트워크 스텁 (fetch/XHR) =====
        // 관찰기가 원본 동작을 변경하지 않는지 검증하기 위해 호출 기록을 남긴다.
        var __fetchCalls = [];
        var __xhrSends = [];
        var __fetchResponseText = '';
        var __xhrResponseText = '';
        var __fetchResultReceived = null;

        window.fetch = function (input, init) {
          __fetchCalls.push({ url: String(input), body: (init && init.body) || '' });
          var fakeResponse = {
            __tag: 'fakeResponse',
            clone: function () {
              return {
                text: function () {
                  return {
                    then: function (ok) {
                      if (typeof __fetchResponseText === 'string') { ok(__fetchResponseText); }
                      return { then: function () {} };
                    }
                  };
                }
              };
            }
          };
          var result = { then: function (cb) { __fetchResultReceived = fakeResponse; cb(fakeResponse); return { then: function () {} }; } };
          return result;
        };

        function FakeXHR() {
          this.__smUrl = '';
          this.listeners = {};
          this.responseType = '';
          this.responseText = '';
        }
        FakeXHR.prototype.open = function (method, url) {
          this.__smUrl = String(url || '');
        };
        FakeXHR.prototype.send = function (body) {
          this.__body = typeof body === 'string' ? body : '';
          __xhrSends.push({ url: this.__smUrl, body: this.__body });
          if (this.responseType === '' || this.responseType === 'text') {
            if (typeof __xhrResponseText === 'string') { this.responseText = __xhrResponseText; }
          }
          var listeners = this.listeners.load || [];
          for (var i = 0; i < listeners.length; i++) {
            try { listeners[i](); } catch (e) {}
          }
        };
        FakeXHR.prototype.addEventListener = function (type, fn) {
          (this.listeners[type] = this.listeners[type] || []).push(fn);
        };
        window.XMLHttpRequest = FakeXHR;
        // 실제 브라우저처럼 전역 이름으로도 접근 가능하게 한다.
        var XMLHttpRequest = FakeXHR;

        // 파서 테스트용 헬퍼 (관찰기 로드 후 사용).
        function __parseSequenceResponse(text) {
          return JSON.stringify(window.__shortsMonitorParser.parseSequenceResponse(text));
        }
        function __decodeSequenceParams(raw) {
          return JSON.stringify(window.__shortsMonitorParser.decodeSequenceParams(raw));
        }
        function __parseRequestBody(kind, text) {
          return JSON.stringify(window.__shortsMonitorParser.parseRequestBody(kind, text));
        }
        function __classifyUrlChange(prev, next) {
          return JSON.stringify(window.__shortsMonitorParser.classifyUrlChange(prev, next));
        }
    """.trimIndent()

    /** 가짜 브라우저 환경 + 관찰기를 로드한 세션. */
    class Session : AutoCloseable {
        private val cx: Context = Context.enter()
        private val scope: Scriptable = cx.initStandardObjects()

        init {
            cx.languageVersion = Context.VERSION_ES6
            cx.evaluateString(scope, HARNESS_JS, "harness", 1, null)
        }

        /** 피드 항목을 고정 HTML로 설정한다 (관찰기 로드 전이면 초기 목록이 된다). */
        fun setItems(html: String) = eval("__setItems(${quote(html)});")

        /** 피드 컨테이너 자체를 새 요소로 교체한다. */
        fun rebuildFeed(html: String) = eval("__rebuildFeed(${quote(html)});")

        /** index 번째 항목을 활성 영상으로 설정한다. */
        fun setActive(index: Int) = eval("__setActive($index);")

        /** 가상 시간을 [ms]만큼 진행하고 만료된 타이머를 실행한다. */
        fun flushTimers(ms: Long) = eval("__flushTimers($ms);")

        fun clearMessages() = eval("__clearMessages();")

        /** 관찰기 스크립트를 로드한다. 피드가 준비된 뒤 호출한다. */
        fun loadObserver() {
            cx.evaluateString(scope, ShortsObserverScript.script, "shorts-observer", 1, null)
        }

        // ===== 네트워크 테스트 헬퍼 =====

        /** fetch 응답 본문을 설정한다. */
        fun setFetchResponse(text: String) = eval("__fetchResponseText = ${quote(text)};")

        /** XHR 응답 본문을 설정한다. */
        fun setXhrResponse(text: String) = eval("__xhrResponseText = ${quote(text)};")

        /** 관찰 대상 URL로 fetch를 호출한다. */
        fun fetch(url: String, body: String) = eval("window.fetch(${quote(url)}, { body: ${quote(body)} });")

        /** 관찰 대상 URL로 XHR 요청을 보낸다. */
        fun xhrSend(url: String, body: String) =
            eval("var __x = new XMLHttpRequest(); __x.open('POST', ${quote(url)}); __x.send(${quote(body)});")

        /** fetch 호출 기록 (JSON). */
        fun fetchCalls(): JSONArray = JSONArray(eval("JSON.stringify(__fetchCalls)"))

        /** XHR send 기록 (JSON). */
        fun xhrSends(): JSONArray = JSONArray(eval("JSON.stringify(__xhrSends)"))

        /** 원본 fetch 소비자에게 전달된 응답이 그대로인지 확인용. */
        fun fetchResultTag(): String = eval("__fetchResultReceived ? __fetchResultReceived.__tag : 'null'")

        /** 네트워크 파서: 시퀀스 응답 분석 결과 (JSON). */
        fun parseSequenceResponse(text: String): String =
            eval("__parseSequenceResponse(${quote(text)})")

        /** 네트워크 파서: sequenceParams 디코딩 결과 (JSON). */
        fun decodeSequenceParams(raw: String): String =
            eval("__decodeSequenceParams(${quote(raw)})")

        /** 네트워크 파서: 요청 본문 분석 결과 (JSON). */
        fun parseRequestBody(kind: String, text: String): String =
            eval("__parseRequestBody(${quote(kind)}, ${quote(text)})")

        /** URL 변경 유형 분류 결과 (JSON). */
        fun classifyUrlChange(prev: String, next: String): String =
            eval("__classifyUrlChange(${quote(prev)}, ${quote(next)})")

        /** 캡처된 관찰기 메시지 (JSON 파싱). */
        fun messages(): JSONArray = JSONArray(eval("JSON.stringify(__messages)"))

        override fun close() {
            Context.exit()
        }

        private fun eval(code: String): String =
            Context.toString(cx.evaluateString(scope, code, "harness-call", 1, null))

        private fun quote(value: String): String =
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    }

    fun newSession(): Session = Session()
}
