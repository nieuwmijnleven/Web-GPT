package com.shortsmonitor.core.observer

/**
 * WebView에 주입하는 JavaScript 관찰기.
 *
 * 페이지 내부에서 다음을 수행한다.
 * - DOM 관찰: MutationObserver로 쇼츠 항목 추가/제거/순서 변경, 활성 영상 변경,
 *   쇼츠 컨테이너 재생성, 주소 변경(SPA 내부 탐색 포함), 전체 재로드 감지
 * - 네트워크 관찰: 문서 시작 시점에 `fetch`/`XMLHttpRequest`를 관찰해
 *   `reel_watch_sequence`, `reel_item_watch`, `player` 요청/응답에서
 *   최소한의 안전한 정보(식별값·해시·존재 여부·길이)만 추출한다.
 *   원본 요청/응답 동작은 변경하지 않는다.
 *   (fetch는 반드시 `clone()`을 읽고 원본 Response를 소비하지 않으며,
 *   XHR은 `addEventListener`로만 리스너를 추가해 원래 이벤트 흐름을 바꾸지 않는다.)
 *
 * 메시지는 `window.shortsMonitorBridge.postMessage(json)`로 네이티브에 전달하며,
 * 네이티브 쪽 허용 출처 규칙([ObserverBridge.ALLOWED_ORIGIN_RULES])이
 * 유튜브 외 출처의 호출을 차단한다.
 *
 * 같은 영상의 재렌더링은 안정 식별 키가 같으면 중복 스냅샷을 보내지 않는다.
 * 하트비트를 주기적으로 보내 네이티브가 관찰기 중단을 감지하고 재시작할 수 있게 한다.
 * 하트비트에는 DOM 목록 해시를 포함해, 스냅샷이 생략된 상태의 목록 변화를
 * 네이티브가 감지할 수 있게 한다.
 *
 * 보안: 네트워크 응답 본문·쿠키·토큰·continuation 원문은 네이티브로 보내지 않는다.
 * 응답 분석은 [NETWORK_PARSER_VERSION]의 방어적 파서로 수행하며,
 * 실패해도 페이지 예외로 전파하지 않는다(모든 분석 로직 격리).
 */
object ShortsObserverScript {

    /** addWebMessageListener로 등록하는 네이티브 통신 객체 이름. */
    const val BRIDGE_OBJECT_NAME = "shortsMonitorBridge"

    /** 중복 주입 방지 플래그. */
    const val GUARD_FLAG = "__shortsMonitorInjected"

    /** 네이티브가 재시작을 요청할 때 호출하는 전역 함수 이름. */
    const val RESTART_FUNCTION = "__shortsMonitorRestart"

    const val OBSERVER_VERSION = "1.1.0"

    /** 네트워크 시퀀스 파서 버전 (응답 구조 변경 시 올린다). */
    const val NETWORK_PARSER_VERSION = "1.0.0"

    /** 하트비트 주기. */
    const val HEARTBEAT_INTERVAL_MS = 5_000L

    /** 스냅샷 디바운스 시간. */
    const val SNAPSHOT_DEBOUNCE_MS = 200L

    /** 네트워크 응답 분석에 허용하는 최대 문자 수. */
    const val MAX_RESPONSE_CHARS = 2_000_000

    /** 단일 시퀀스에서 허용하는 최대 항목 수. */
    const val MAX_SEQUENCE_ITEMS = 200

    val script: String = """
        (function () {
          if (window.${'$'}GUARD_FLAG) { return; }
          window.${'$'}GUARD_FLAG = true;

          var VERSION = '${'$'}OBSERVER_VERSION';
          var BRIDGE_NAME = '${'$'}BRIDGE_OBJECT_NAME';
          var HEARTBEAT_INTERVAL = ${'$'}HEARTBEAT_INTERVAL;
          var SNAPSHOT_DEBOUNCE = ${'$'}SNAPSHOT_DEBOUNCE;
          var NETWORK_PARSER_VERSION = '${'$'}NETWORK_PARSER_VERSION';
          var MAX_RESPONSE_CHARS = ${'$'}MAX_RESPONSE_CHARS;
          var MAX_SEQUENCE_ITEMS = ${'$'}MAX_SEQUENCE_ITEMS;

          // ===== 안전 해시 (djb2). 민감 원문은 저장하지 않고 해시만 사용한다. =====
          function hashOf(text) {
            if (!text) return '';
            var h = 5381;
            for (var i = 0; i < text.length; i++) {
              h = ((h << 5) + h + text.charCodeAt(i)) | 0;
            }
            return (h >>> 0).toString(36);
          }

          function hashSafe(value) {
            if (typeof value !== 'string' || !value) return '';
            return hashOf(value);
          }

          // 문자열을 지정 길이로 자른다. 로그/메시지 필드 제한용.
          function clip(value, max) {
            if (typeof value !== 'string') return '';
            return value.length > max ? value.slice(0, max) : value;
          }

          // ===== 유튜브 DOM 선택자 중앙 관리 =====
          // 유튜브 DOM 변경 시 이 블록만 수정하면 된다.
          var SELECTORS = {
            feed: [
              'ytm-shorts',
              'ytd-shorts',
              '#shorts-container',
              'ytd-reel-video-renderer'
            ],
            itemRoots: [
              'ytm-shorts-video-renderer',
              'ytd-reel-video-renderer'
            ],
            item: [
              'ytm-shorts-video-renderer',
              'ytd-reel-video-renderer',
              'a[href*="/shorts/"]',
              'ytm-shorts-lockup-view-model'
            ],
            title: [
              'ytm-shorts-lockup-view-model__title',
              '.ytm-shorts-lockup-view-model__title',
              '.title',
              '#video-title'
            ],
            channel: [
              '.channel-name',
              '.ytm-shorts-lockup-view-model__subtitle',
              'a.yt-simple-endpoint[href*="/@"]'
            ],
            thumbnail: [
              'img.yt-core-image',
              'img'
            ]
          };

          // ===== ShortsDomAdapter =====
          // 선택자가 변경될 경우 이 어댑터만 교체한다.
          var ShortsDomAdapter = {
            findFeedContainer: function () {
              var items = ShortsDomAdapter.findShortItems(document);
              // 피드 후보는 개별 영상 요소를 반환해서는 안 된다.
              // 후보가 항목 요소 자체이거나 항목을 포함하지 않으면 버린다.
              for (var i = 0; i < SELECTORS.feed.length; i++) {
                var el = document.querySelector(SELECTORS.feed[i]);
                if (el && !ShortsDomAdapter.isItemElement(el) && ShortsDomAdapter.containsItem(el)) {
                  return el;
                }
              }
              // 후보가 없으면 첫 항목의 부모를 사용한다. 부모가 항목 자체면 버린다.
              if (items.length > 0) {
                var parent = items[0].parentElement;
                if (parent && !ShortsDomAdapter.isItemElement(parent)) { return parent; }
              }
              return null;
            },

            isItemElement: function (el) {
              for (var i = 0; i < SELECTORS.itemRoots.length; i++) {
                if (el.tagName && el.tagName.toLowerCase() === SELECTORS.itemRoots[i].toLowerCase()) {
                  return true;
                }
              }
              return false;
            },

            containsItem: function (el) {
              for (var i = 0; i < SELECTORS.itemRoots.length; i++) {
                if (el.querySelector(SELECTORS.itemRoots[i])) { return true; }
              }
              return false;
            },

            // 요소에서 가장 가까운 항목 루트를 찾는다. 없으면 자기 자신.
            closestItemRoot: function (el) {
              var cur = el;
              while (cur && cur !== document) {
                if (ShortsDomAdapter.isItemElement(cur)) { return cur; }
                cur = cur.parentElement;
              }
              return el;
            },

            findShortItems: function (container) {
              var items = [];
              var stats = {};
              for (var i = 0; i < SELECTORS.item.length; i++) {
                var sel = SELECTORS.item[i];
                stats[sel] = 0;
                var found = container.querySelectorAll(sel);
                for (var j = 0; j < found.length; j++) {
                  var el = found[j];
                  stats[sel]++;
                  // a[href*="/shorts/"] 등 하위 요소는 항목 루트로 정규화해 중복을 막는다.
                  var root = ShortsDomAdapter.closestItemRoot(el);
                  if (items.indexOf(root) < 0) { items.push(root); }
                }
              }
              return { items: items, stats: stats };
            },

            extractVideoId: function (el) {
              var attrs = ['data-video-id', 'video-id', 'data-id'];
              for (var i = 0; i < attrs.length; i++) {
                var v = el.getAttribute(attrs[i]);
                if (v) { return v; }
              }
              var url = ShortsDomAdapter.extractVideoUrl(el);
              var m = url.match(/shorts\/([A-Za-z0-9_-]{6,})/);
              return m ? m[1] : '';
            },

            extractVideoUrl: function (el) {
              var a = el.tagName === 'A' ? el : el.querySelector('a[href*="/shorts/"]');
              if (a && a.href) { return a.href; }
              return '';
            },

            extractTitle: function (el) {
              for (var i = 0; i < SELECTORS.title.length; i++) {
                var n = el.querySelector(SELECTORS.title[i]);
                if (n && n.textContent) { return n.textContent.replace(/\s+/g, ' ').trim(); }
              }
              return '';
            },

            extractChannel: function (el) {
              for (var i = 0; i < SELECTORS.channel.length; i++) {
                var n = el.querySelector(SELECTORS.channel[i]);
                if (n && n.textContent) { return n.textContent.replace(/\s+/g, ' ').trim(); }
              }
              return '';
            },

            extractThumbnail: function (el) {
              for (var i = 0; i < SELECTORS.thumbnail.length; i++) {
                var img = el.querySelector(SELECTORS.thumbnail[i]);
                if (img) {
                  var src = img.getAttribute('src') || img.getAttribute('data-thumb') || '';
                  if (src) { return src; }
                }
              }
              return '';
            },

            /**
             * 활성 영상 판정. 단일 신호에 의존하지 않고 여러 신호를 조합한다.
             * 신뢰도와 사용 신호 목록을 함께 반환해 진단에 사용한다.
             * 신호: is-active 속성 > URL 영상 식별값 > 재생 중 video > 화면 중앙 > aria 상태 > activeElement.
             */
            detectActiveItem: function (items) {
              var signals = [];
              // 1) is-active 속성 (신뢰도 높음)
              for (var i = 0; i < items.length; i++) {
                if (items[i].hasAttribute('is-active')) {
                  return { item: items[i], index: i, confidence: 0.9, signals: ['is_active'] };
                }
              }
              // 2) URL의 영상 식별값 (주소가 /shorts/<id>인 경우)
              var urlId = shortVideoIdFromUrl(location.href);
              if (urlId) {
                for (var j = 0; j < items.length; j++) {
                  var vid = ShortsDomAdapter.extractVideoId(items[j]);
                  if (vid && vid === urlId) {
                    return { item: items[j], index: j, confidence: 0.85, signals: ['url_video_id'] };
                  }
                }
              }
              // 3) 재생 중인 video 요소 (paused=false)
              try {
                var videos = document.querySelectorAll('video');
                for (var v = 0; v < videos.length; v++) {
                  if (!videos[v].paused) {
                    for (var k = 0; k < items.length; k++) {
                      if (items[k] === videos[v] || items[k].contains(videos[v])) {
                        return { item: items[k], index: k, confidence: 0.8, signals: ['playing_video'] };
                      }
                    }
                  }
                }
              } catch (e) { signals.push('video_signal_unavailable'); }
              // 4) 화면 중앙에 위치한 요소
              try {
                var centerY = window.innerHeight / 2;
                for (var m = 0; m < items.length; m++) {
                  var rect = items[m].getBoundingClientRect();
                  if (rect && rect.top <= centerY && rect.bottom >= centerY) {
                    return { item: items[m], index: m, confidence: 0.6, signals: ['viewport_center'] };
                  }
                }
              } catch (e) { signals.push('rect_signal_unavailable'); }
              // 5) aria-current/aria-selected
              for (var n = 0; n < items.length; n++) {
                var aria = items[n].getAttribute('aria-current') || items[n].getAttribute('aria-selected');
                if (aria && aria !== 'false') {
                  return { item: items[n], index: n, confidence: 0.55, signals: ['aria_state'] };
                }
              }
              // 6) document.activeElement
              var active = document.activeElement;
              if (active) {
                for (var p = 0; p < items.length; p++) {
                  if (items[p] === active || items[p].contains(active)) {
                    return { item: items[p], index: p, confidence: 0.5, signals: ['active_element'] };
                  }
                }
              }
              // 7) 항목이 하나뿐이면 그것을 활성으로 본다 (낮은 신뢰도)
              if (items.length === 1) {
                return { item: items[0], index: 0, confidence: 0.3, signals: ['single_item'] };
              }
              return { item: null, index: -1, confidence: 0, signals: signals };
            }
          };

          // ===== 식별 =====
          // G단계 식별 우선순위: 영상 식별값 > 주소 > DOM 데이터 속성 > 썸네일 > 해시
          function identityOf(item) {
            if (item.videoId) { return { source: 'video_id', key: item.videoId }; }
            if (item.url) { return { source: 'url', key: item.url }; }
            if (item.dataKey) { return { source: 'data_attr', key: item.dataKey }; }
            if (item.thumbnail) { return { source: 'thumbnail', key: item.thumbnail }; }
            return { source: 'hash', key: hashOf(item.title + '|' + item.channel + '|' + item.thumbnail) };
          }

          function buildShort(el) {
            var videoId = ShortsDomAdapter.extractVideoId(el);
            var url = ShortsDomAdapter.extractVideoUrl(el);
            var title = ShortsDomAdapter.extractTitle(el);
            var channel = ShortsDomAdapter.extractChannel(el);
            var thumbnail = ShortsDomAdapter.extractThumbnail(el);
            var identity = identityOf({
              videoId: videoId,
              url: url,
              title: title,
              channel: channel,
              thumbnail: thumbnail,
              dataKey: el.getAttribute('data-video-id') || el.getAttribute('data-id') || ''
            });
            return {
              videoId: videoId,
              url: url,
              title: title,
              channel: channel,
              thumbnail: thumbnail,
              identitySource: identity.source,
              identityKey: identity.key
            };
          }

          // ===== 스냅샷 =====
          function buildSnapshot() {
            var feed = ShortsDomAdapter.findFeedContainer();
            if (!feed) { return null; }
            var collected = ShortsDomAdapter.findShortItems(feed);
            var domItems = collected.items;
            if (domItems.length === 0) { return null; }
            var shorts = [];
            var seenKeys = {};
            var seenVideoIds = {};
            var duplicatesDropped = 0;
            for (var i = 0; i < domItems.length; i++) {
              var s = buildShort(domItems[i]);
              if (!s.identityKey || seenKeys[s.identityKey]) { continue; }
              // 영상 식별값으로도 중복 제거한다 (같은 영상이 다른 노드로 중복 수집된 경우).
              if (s.videoId && seenVideoIds[s.videoId]) { duplicatesDropped++; continue; }
              if (s.videoId) { seenVideoIds[s.videoId] = true; }
              seenKeys[s.identityKey] = true;
              shorts.push(s);
            }
            var active = ShortsDomAdapter.detectActiveItem(domItems);
            return {
              shorts: shorts,
              activeId: active.item ? ShortsDomAdapter.extractVideoId(active.item) : '',
              activeIndex: active.index,
              activeConfidence: active.confidence,
              activeSignals: active.signals,
              selectorStats: collected.stats,
              duplicatesDropped: duplicatesDropped
            };
          }

          // ===== 상태 =====
          var seq = 0;
          var revision = 0;
          var lastKeys = null;
          var lastKeysHash = '';
          var currentActiveKey = '';
          var lastContainer = null;
          var feedObserver = null;
          var heartbeatTimer = null;
          var urlTimer = null;
          var snapshotTimer = null;
          var activeTimer = null;
          var lastHref = location.href;

          function postMessage(msg) {
            msg.seq = ++seq;
            msg.ts = Date.now();
            var json = JSON.stringify(msg);
            try {
              if (window[BRIDGE_NAME] && typeof window[BRIDGE_NAME].postMessage === 'function') {
                window[BRIDGE_NAME].postMessage(json);
              }
            } catch (e) {
              if (window.console) { window.console.error('shorts monitor bridge error', e); }
            }
          }

          function keysOf(shorts) {
            var keys = [];
            for (var i = 0; i < shorts.length; i++) { keys.push(shorts[i].identityKey); }
            return keys;
          }

          function sameKeys(a, b) {
            if (a.length !== b.length) { return false; }
            for (var i = 0; i < a.length; i++) { if (a[i] !== b[i]) { return false; } }
            return true;
          }

          function classifyChange(prevKeys, nextKeys) {
            if (!prevKeys) { return 'initial'; }
            var prevSet = {};
            var nextSet = {};
            for (var i = 0; i < prevKeys.length; i++) { prevSet[prevKeys[i]] = true; }
            for (var j = 0; j < nextKeys.length; j++) { nextSet[nextKeys[j]] = true; }
            var added = 0;
            var removed = 0;
            for (var k = 0; k < nextKeys.length; k++) { if (!prevSet[nextKeys[k]]) { added++; } }
            for (var l = 0; l < prevKeys.length; l++) { if (!nextSet[prevKeys[l]]) { removed++; } }
            if (added > 0 && removed === 0) { return 'item_added'; }
            if (removed > 0 && added === 0) { return 'item_removed'; }
            return 'order_changed';
          }

          // 목록 안정 식별 키 배열의 해시. 하트비트에 포함해 스냅샷 생략 상태의 변화를 감지한다.
          function listHashOf(keys) {
            return hashOf(keys.join('|'));
          }

          function publishSnapshot(hint) {
            var snapshot = buildSnapshot();
            if (!snapshot) { return; }
            var keys = keysOf(snapshot.shorts);
            // 같은 영상의 재렌더링: 안정 키가 같으면 중복 스냅샷을 보내지 않는다.
            if (sameKeys(keys, lastKeys || [])) {
              checkActiveChange(snapshot);
              return;
            }
            var reason = classifyChange(lastKeys, keys);
            if (hint === 'full_reload') { reason = 'full_reload'; }
            if (hint === 'navigation') { reason = 'navigation'; }
            lastKeys = keys;
            lastKeysHash = listHashOf(keys);
            revision++;
            postMessage({
              type: 'list_snapshot',
              data: {
                revision: revision,
                reason: reason,
                url: location.href,
                shorts: snapshot.shorts,
                selectorStats: snapshot.selectorStats,
                duplicatesDropped: snapshot.duplicatesDropped,
                activeConfidence: snapshot.activeConfidence,
                activeSignals: snapshot.activeSignals
              }
            });
            checkActiveChange(snapshot);
          }

          function checkActiveChange(snapshot) {
            if (!snapshot.activeId) { return; }
            if (snapshot.activeId === currentActiveKey) { return; }
            currentActiveKey = snapshot.activeId;
            var target = null;
            for (var i = 0; i < snapshot.shorts.length; i++) {
              if (snapshot.shorts[i].videoId === snapshot.activeId ||
                  snapshot.shorts[i].identityKey === snapshot.activeId) {
                target = snapshot.shorts[i];
                break;
              }
            }
            if (!target && snapshot.activeIndex >= 0 && snapshot.activeIndex < snapshot.shorts.length) {
              target = snapshot.shorts[snapshot.activeIndex];
            }
            if (!target) { return; }
            postMessage({
              type: 'active_short_changed',
              data: {
                short: target,
                index: snapshot.activeIndex,
                count: snapshot.shorts.length,
                confidence: snapshot.activeConfidence,
                signals: snapshot.activeSignals
              }
            });
          }

          function publishPageInfo(urlChangeType) {
            var snapshot = buildSnapshot();
            postMessage({
              type: 'page_info',
              data: {
                url: location.href,
                title: document.title || '',
                activeVideoId: snapshot ? snapshot.activeId : '',
                urlChangeType: urlChangeType || ''
              }
            });
          }

          function publishReady(hint) {
            postMessage({
              type: 'observer_ready',
              data: {
                observerVersion: VERSION,
                adapterVersion: '${'$'}ADAPTER_VERSION',
                url: location.href,
                title: document.title || ''
              }
            });
            publishPageInfo('');
            publishSnapshot(hint);
          }

          // ===== 변화 감지 =====
          function scheduleSnapshot() {
            if (snapshotTimer) { clearTimeout(snapshotTimer); }
            snapshotTimer = setTimeout(function () {
              snapshotTimer = null;
              publishSnapshot();
            }, SNAPSHOT_DEBOUNCE);
          }

          function scheduleActiveCheck() {
            if (activeTimer) { clearTimeout(activeTimer); }
            activeTimer = setTimeout(function () {
              activeTimer = null;
              var snapshot = buildSnapshot();
              if (snapshot) { checkActiveChange(snapshot); }
            }, 100);
          }

          function startObservers() {
            if (feedObserver) {
              try { feedObserver.disconnect(); } catch (e) {}
              feedObserver = null;
            }
            var feed = ShortsDomAdapter.findFeedContainer();
            if (!feed) {
              postMessage({
                type: 'observer_error',
                data: { code: 'feed_not_found', message: 'shorts feed container not found' }
              });
              setTimeout(startObservers, 2000);
              return;
            }
            lastContainer = feed;
            feedObserver = new MutationObserver(function (mutations) {
              var activeChanged = false;
              for (var i = 0; i < mutations.length; i++) {
                if (mutations[i].type === 'attributes' && mutations[i].attributeName === 'is-active') {
                  activeChanged = true;
                }
              }
              if (activeChanged) { scheduleActiveCheck(); }
              scheduleSnapshot();
            });
            feedObserver.observe(feed, {
              childList: true,
              subtree: true,
              attributes: true,
              attributeFilter: ['is-active']
            });
          }

          function checkContainer() {
            var current = ShortsDomAdapter.findFeedContainer();
            if (current && current !== lastContainer) {
              lastKeys = null;
              lastKeysHash = '';
              currentActiveKey = '';
              lastContainer = current;
              startObservers();
              postMessage({ type: 'dom_rebuilt', data: { revision: revision } });
              scheduleSnapshot();
            }
          }

          // ===== URL 변경 유형 분류 =====
          // 같은 Shorts 시퀀스 안에서 영상 주소만 바뀐 경우(/shorts/<id> → /shorts/<id2>)는
          // 새 탐색으로 보지 않아 네트워크 시퀀스 기준과 삽입 후보를 초기화하지 않는다.
          function isShortsVideoPath(path) {
            return /^\/shorts\/[A-Za-z0-9_-]{6,}/.test(path);
          }

          function classifyUrlChange(prevHref, nextHref) {
            try {
              var prevPath = pathOf(prevHref);
              var nextPath = pathOf(nextHref);
              if (prevPath === nextPath) { return 'same_sequence_active_change'; }
              if (isShortsVideoPath(prevPath) && isShortsVideoPath(nextPath)) {
                return 'same_sequence_active_change';
              }
              if (isShortsVideoPath(prevPath) && nextPath === '/shorts') {
                return 'same_sequence_active_change';
              }
              if (prevPath === '/shorts' && isShortsVideoPath(nextPath)) {
                return 'same_sequence_active_change';
              }
            } catch (e) { return 'new_context'; }
            return 'new_context';
          }

          function pathOf(href) {
            try {
              var u = new URL(href);
              return u.pathname;
            } catch (e) {
              var m = href.match(/^https?:\/\/[^\/]+(\/[^?#]*)?/);
              return m ? (m[1] || '/') : '/';
            }
          }

          function onUrlChanged() {
            var prevHref = lastHref;
            lastHref = location.href;
            var changeType = classifyUrlChange(prevHref, location.href);
            publishPageInfo(changeType);
            if (changeType === 'same_sequence_active_change') {
              // 같은 시퀀스 안의 활성 영상 변경: 기준 목록과 후보를 유지한다.
              scheduleActiveCheck();
              return;
            }
            // 실제 탐색 컨텍스트 변경: 새 기준을 생성한다.
            lastKeys = null;
            lastKeysHash = '';
            currentActiveKey = '';
            revision = 0;
            scheduleSnapshot();
          }

          function startUrlWatch() {
            var origPush = history.pushState;
            history.pushState = function () {
              var result = origPush.apply(this, arguments);
              onUrlChanged();
              return result;
            };
            var origReplace = history.replaceState;
            history.replaceState = function () {
              var result = origReplace.apply(this, arguments);
              onUrlChanged();
              return result;
            };
            window.addEventListener('popstate', onUrlChanged);
            urlTimer = setInterval(function () {
              if (location.href !== lastHref) { onUrlChanged(); }
              checkContainer();
            }, 1000);
          }

          function startHeartbeat() {
            heartbeatTimer = setInterval(function () {
              var snapshot = buildSnapshot();
              postMessage({
                type: 'heartbeat',
                data: {
                  revision: revision,
                  shortCount: snapshot ? snapshot.shorts.length : 0,
                  activeVideoId: snapshot ? snapshot.activeId : '',
                  observerVersion: VERSION,
                  listHash: lastKeysHash
                }
              });
              NetworkObserver.publishStatus(snapshot ? snapshot.shorts.length : 0);
            }, HEARTBEAT_INTERVAL);
          }

          // ============================================================
          // ===== 네트워크 관찰기 =====
          // ============================================================
          function classifyRequest(url) {
            if (!url) { return 'other'; }
            if (url.indexOf('reel_watch_sequence') >= 0) { return 'reel_watch_sequence'; }
            if (url.indexOf('reel_item_watch') >= 0) { return 'reel_item_watch'; }
            if (url.indexOf('/player') >= 0 || url.indexOf('player?') >= 0) { return 'player'; }
            return 'other';
          }

          // base64(URL-safe) 디코드. atob에 의존하지 않고 직접 구현한다.
          function base64UrlDecode(str) {
            try {
              var b64 = String(str).replace(/-/g, '+').replace(/_/g, '/');
              while (b64.length % 4 !== 0) { b64 += '='; }
              var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
              var lookup = {};
              for (var i = 0; i < chars.length; i++) { lookup[chars.charAt(i)] = i; }
              var out = [];
              var buffer = 0;
              var bits = 0;
              for (var j = 0; j < b64.length; j++) {
                var c = b64.charAt(j);
                if (c === '=') { break; }
                if (!(c in lookup)) { return null; }
                buffer = (buffer << 6) | lookup[c];
                bits += 6;
                if (bits >= 8) {
                  bits -= 8;
                  out.push((buffer >> bits) & 0xff);
                }
              }
              return out;
            } catch (e) { return null; }
          }

          /**
           * sequenceParams 디코딩.
           * protobuf 스타일 walker: length-delimited 필드를 재귀적으로 탐색해
           * 정확히 11자의 영상 식별값 토큰을 순서대로 수집한다.
           * (HAR 분석 결과: sequenceParams에는 현재 영상과 이후 영상의 순서가 포함되어 있다.)
           * 구조가 달라져도 11자 토큰 정규식으로 폴백하므로 파싱이 전체 실패하지 않는다.
           * 원문은 저장하지 않는다.
           */
          function walkProto(bytes, start, end, depth, out) {
            if (depth > 6 || start >= end) { return; }
            var i = start;
            while (i < end) {
              var tag = bytes[i];
              var wt = tag & 0x07;
              i++;
              if (wt === 0) {
                while (i < end && (bytes[i] & 0x80)) { i++; }
                if (i < end) { i++; }
              } else if (wt === 1) {
                i += 8;
              } else if (wt === 2) {
                var len = 0;
                var shift = 0;
                while (i < end) {
                  var b = bytes[i];
                  len |= (b & 0x7f) << shift;
                  i++;
                  shift += 7;
                  if (!(b & 0x80)) { break; }
                }
                if (i + len > end) { break; }
                if (len === 11) {
                  var s = '';
                  for (var j = 0; j < 11; j++) { s += String.fromCharCode(bytes[i + j]); }
                  if (/^[A-Za-z0-9_-]+$/.test(s)) { out.push(s); }
                } else if (len > 0 && (bytes[i] & 0x07) <= 5) {
                  walkProto(bytes, i, i + len, depth + 1, out);
                }
                i += len;
              } else if (wt === 5) {
                i += 4;
              } else {
                break;
              }
            }
          }

          function decodeSequenceParams(raw) {
            var result = {
              videoIds: [],
              decoded: false,
              error: '',
              hash: hashOf(raw || ''),
              length: (raw || '').length
            };
            if (!raw) { result.error = 'empty'; return result; }
            var bytes = base64UrlDecode(raw);
            if (!bytes) { result.error = 'base64_decode_failed'; return result; }
            var ids = [];
            walkProto(bytes, 0, bytes.length, 0, ids);
            var seen = {};
            var deduped = [];
            for (var i = 0; i < ids.length; i++) {
              if (!seen[ids[i]]) { seen[ids[i]] = true; deduped.push(ids[i]); }
            }
            if (deduped.length === 0) {
              // 폴백: 디코딩된 바이트에서 11자 토큰을 찾는다.
              var bin = '';
              for (var k = 0; k < bytes.length; k++) { bin += String.fromCharCode(bytes[k]); }
              var m;
              var re = /[A-Za-z0-9_-]{11}/g;
              while ((m = re.exec(bin)) !== null) {
                if (!seen[m[0]]) { seen[m[0]] = true; deduped.push(m[0]); }
              }
            }
            result.decoded = deduped.length > 0;
            result.videoIds = deduped;
            if (deduped.length === 0) { result.error = 'no_video_ids_found'; }
            return result;
          }

          // 객체의 키 골격만 해시한다. 값은 타입·길이로 대체해 민감정보를 남기지 않는다.
          function skeletonHash(obj) {
            function skeleton(value, depth) {
              if (depth > 12) { return 'D'; }
              if (value === null) { return 'null'; }
              var t = typeof value;
              if (t === 'string') { return 'str:' + value.length; }
              if (t === 'number') { return 'num'; }
              if (t === 'boolean') { return 'bool'; }
              if (Object.prototype.toString.call(value) === '[object Array]') {
                var parts = [];
                for (var i = 0; i < Math.min(value.length, 20); i++) {
                  parts.push(skeleton(value[i], depth + 1));
                }
                return 'arr[' + value.length + '](' + parts.join(',') + ')';
              }
              if (t === 'object') {
                var keys = Object.keys(value).sort();
                var kparts = [];
                for (var j = 0; j < keys.length; j++) {
                  kparts.push(keys[j] + ':' + skeleton(value[keys[j]], depth + 1));
                }
                return 'obj{' + kparts.join(',') + '}';
              }
              return t;
            }
            return hashOf(skeleton(obj, 0));
          }

          function findVideoId(node, depth) {
            if (depth > 8 || !node || typeof node !== 'object') { return ''; }
            if (Object.prototype.toString.call(node) === '[object Array]') {
              for (var i = 0; i < node.length; i++) {
                var r = findVideoId(node[i], depth + 1);
                if (r) { return r; }
              }
              return '';
            }
            if (typeof node.videoId === 'string' && node.videoId) { return node.videoId; }
            var keys = Object.keys(node);
            for (var j = 0; j < keys.length; j++) {
              var r2 = findVideoId(node[keys[j]], depth + 1);
              if (r2) { return r2; }
            }
            return '';
          }

          function findContinuation(node, depth) {
            if (depth > 8 || !node || typeof node !== 'object') { return ''; }
            if (Object.prototype.toString.call(node) === '[object Array]') {
              for (var i = 0; i < node.length; i++) {
                var r = findContinuation(node[i], depth + 1);
                if (r) { return r; }
              }
              return '';
            }
            if (typeof node.continuation === 'string' && node.continuation) { return node.continuation; }
            if (typeof node.ctoken === 'string' && node.ctoken) { return node.ctoken; }
            var keys = Object.keys(node);
            for (var j = 0; j < keys.length; j++) {
              var r2 = findContinuation(node[keys[j]], depth + 1);
              if (r2) { return r2; }
            }
            return '';
          }

          /**
           * 요청 본문 분석. sequenceParams는 디코딩된 영상 순서·해시·길이만 남기고,
           * continuation 계열 값은 해시만 남긴다. 민감 원문은 저장하지 않는다.
           */
          function parseRequestBody(kind, bodyText) {
            var out = {
              videoId: '',
              currentVideoId: '',
              sequenceVideoIds: [],
              sequenceParamsDecoded: false,
              sequenceParamsHash: '',
              sequenceParamsLength: 0,
              sequenceParamsError: '',
              continuationHash: '',
              requestContextHash: '',
              clientName: '',
              clientVersion: '',
              bodyStructureHash: '',
              warnings: []
            };
            var obj = null;
            try { obj = JSON.parse(bodyText); } catch (e) {
              out.warnings.push('request_body_parse_failed');
              out.bodyStructureHash = hashOf(bodyText || '');
              return out;
            }
            if (!obj || typeof obj !== 'object') {
              out.warnings.push('request_body_not_object');
              out.bodyStructureHash = hashOf(bodyText || '');
              return out;
            }
            out.bodyStructureHash = skeletonHash(obj);
            if (obj.context && typeof obj.context === 'object') {
              out.requestContextHash = skeletonHash(obj.context);
              if (obj.context.client && typeof obj.context.client === 'object') {
                out.clientName = clip(obj.context.client.clientName || '', 64);
                out.clientVersion = clip(obj.context.client.clientVersion || '', 64);
              }
            }
            var cont = findContinuation(obj);
            if (cont) { out.continuationHash = hashOf(cont); }
            if (kind === 'reel_watch_sequence') {
              var sp = (obj && typeof obj.sequenceParams === 'string') ? obj.sequenceParams : '';
              var dec = decodeSequenceParams(sp);
              out.sequenceParamsDecoded = dec.decoded;
              out.sequenceParamsHash = dec.hash;
              out.sequenceParamsLength = dec.length;
              out.sequenceParamsError = dec.error;
              out.sequenceVideoIds = dec.videoIds;
              out.currentVideoId = dec.videoIds[0] || '';
              if (dec.error) { out.warnings.push('sequence_params_' + dec.error); }
            } else {
              var vid = findVideoId(obj);
              if (vid) {
                out.videoId = vid;
              } else {
                out.warnings.push('video_id_not_found');
              }
            }
            return out;
          }

          function looksLikeEntry(node) {
            if (!node || typeof node !== 'object') { return false; }
            var cmd = node.command;
            return !!cmd && typeof cmd === 'object' && (cmd.reelWatchEndpoint || cmd.reelNonVideoContentEndpoint);
          }

          function findEntriesArray(node, depth) {
            if (depth > 8 || !node || typeof node !== 'object') { return null; }
            if (Object.prototype.toString.call(node) === '[object Array]') {
              if (node.length > 0 && looksLikeEntry(node[0])) { return node; }
              for (var i = 0; i < node.length; i++) {
                var r = findEntriesArray(node[i], depth + 1);
                if (r) { return r; }
              }
              return null;
            }
            var keys = Object.keys(node);
            for (var j = 0; j < keys.length; j++) {
              var r2 = findEntriesArray(node[keys[j]], depth + 1);
              if (r2) { return r2; }
            }
            return null;
          }

          // 여러 후보 경로로 항목 배열을 찾는다. 구조 변경 시 전체 파싱이 실패하지 않게 한다.
          function extractEntries(obj) {
            if (Object.prototype.toString.call(obj.entries) === '[object Array]') {
              return { list: obj.entries, shape: 'root_entries' };
            }
            var nested = obj.reelWatchSequenceRenderer;
            if (nested && typeof nested === 'object') {
              if (Object.prototype.toString.call(nested.entries) === '[object Array]') {
                return { list: nested.entries, shape: 'reelWatchSequenceRenderer_entries' };
              }
              if (Object.prototype.toString.call(nested.items) === '[object Array]') {
                return { list: nested.items, shape: 'reelWatchSequenceRenderer_items' };
              }
            }
            var found = findEntriesArray(obj, 0);
            if (found) { return { list: found, shape: 'deep_entries' }; }
            return null;
          }

          function parseEntry(ent, position) {
            if (!ent || typeof ent !== 'object') { return null; }
            var cmd = ent.command || {};
            var rwe = cmd.reelWatchEndpoint || null;
            var videoId = (rwe && typeof rwe.videoId === 'string') ? rwe.videoId : '';
            var kind = 'video';
            var nonVideoKind = '';
            if (!rwe) {
              kind = 'non_video';
              nonVideoKind = cmd.reelNonVideoContentEndpoint ? 'reel_non_video_content' : 'unknown';
            }
            return {
              position: position,
              videoId: videoId,
              entryKind: kind,
              nonVideoKind: nonVideoKind,
              isCurrent: position === 0 && kind === 'video',
              hasPlayerParams: !!(rwe && rwe.playerParams),
              hasContinuation: !!(rwe && rwe.softRefreshContinuation),
              trackingHash: hashSafe(ent.trackingParams) || hashSafe(cmd.clickTrackingParams),
              playerParamsHash: hashSafe(rwe && rwe.playerParams),
              continuationHash: hashSafe(rwe && rwe.softRefreshContinuation)
            };
          }

          /**
           * reel_watch_sequence 응답 분석. 불투명한 실행·추적 파라미터는 해시만 남긴다.
           * 파싱 실패는 실패 상태와 경고 코드로만 전달한다.
           */
          function parseSequenceResponse(text) {
            var result = {
              currentVideoId: '',
              sequenceHash: '',
              continuationHash: '',
              trackingHash: '',
              responseContextHash: '',
              parseStatus: 'failed',
              detectedShape: 'unknown',
              warnings: [],
              items: []
            };
            if (!text) { result.warnings.push('response_empty'); return result; }
            if (text.length > MAX_RESPONSE_CHARS) {
              result.warnings.push('response_too_large');
              return result;
            }
            var obj = null;
            try { obj = JSON.parse(text); } catch (e) {
              result.parseStatus = 'failed';
              result.warnings.push('response_json_parse_failed');
              return result;
            }
            if (!obj || typeof obj !== 'object') {
              result.parseStatus = 'failed';
              result.warnings.push('response_not_object');
              return result;
            }
            result.responseContextHash = (obj.responseContext && typeof obj.responseContext === 'object')
              ? skeletonHash(obj.responseContext) : '';
            result.trackingHash = hashSafe(obj.trackingParams);
            var entries = extractEntries(obj);
            if (!entries) {
              result.parseStatus = 'unsupported';
              result.detectedShape = 'unknown';
              result.warnings.push('sequence_structure_unsupported');
              return result;
            }
            result.detectedShape = entries.shape;
            var seen = {};
            for (var i = 0; i < entries.list.length && result.items.length < MAX_SEQUENCE_ITEMS; i++) {
              var parsed = parseEntry(entries.list[i], i);
              if (!parsed) { continue; }
              if (parsed.videoId && seen[parsed.videoId]) {
                result.warnings.push('duplicate_video_id');
                continue;
              }
              if (parsed.videoId) { seen[parsed.videoId] = true; }
              result.items.push(parsed);
            }
            for (var k = 0; k < result.items.length; k++) {
              if (result.items[k].videoId) { result.currentVideoId = result.items[k].videoId; break; }
            }
            if (result.items.length === 0) {
              result.parseStatus = 'failed';
              result.warnings.push('sequence_no_video_ids');
            } else {
              result.parseStatus = result.warnings.length > 0 ? 'partial' : 'parsed';
            }
            var contParts = [];
            var trackParts = [];
            for (var m = 0; m < result.items.length; m++) {
              var it = result.items[m];
              if (it.hasContinuation && it.continuationHash) { contParts.push(it.continuationHash); }
              if (it.trackingHash) { trackParts.push(it.trackingHash); }
            }
            result.continuationHash = contParts.length > 0 ? hashOf(contParts.join('|')) : '';
            result.trackingHash = result.trackingHash || (trackParts.length > 0 ? hashOf(trackParts.join('|')) : '');
            var idList = [];
            for (var n = 0; n < result.items.length; n++) {
              idList.push(result.items[n].videoId || '');
            }
            result.sequenceHash = hashOf(idList.join('|'));
            return result;
          }

          var NetworkObserver = {
            installed: false,
            installedAt: 0,
            firstSequenceRequestAt: 0,
            lastSequenceRequestAt: 0,
            lastSequenceResponseAt: 0,
            lastSequenceVideoCount: 0,
            lastSequenceParseStatus: 'none',
            missedInitialPossible: false,
            lastRequestVideoId: '',
            warnings: [],
            correlationSeq: 0,

            newCorrelation: function () {
              NetworkObserver.correlationSeq++;
              return 'r' + NetworkObserver.correlationSeq + '_' + Date.now();
            },

            warn: function (code, message) {
              NetworkObserver.warnings.push(code + (message ? ':' + message : ''));
              if (NetworkObserver.warnings.length > 50) { NetworkObserver.warnings.shift(); }
              postMessage({
                type: 'network_parse_warning',
                data: { pageUrl: location.href, code: code, message: message || '' }
              });
            },

            onRequestSeen: function (kind, url, bodyText, correlation) {
              var ts = Date.now();
              var parsed = parseRequestBody(kind, bodyText);
              if (kind === 'reel_watch_sequence') {
                if (NetworkObserver.firstSequenceRequestAt === 0) {
                  NetworkObserver.firstSequenceRequestAt = ts;
                }
                NetworkObserver.lastSequenceRequestAt = ts;
                postMessage({
                  type: 'network_sequence_request',
                  data: {
                    pageUrl: location.href,
                    requestUrl: url,
                    correlationId: correlation,
                    requestKind: kind,
                    currentVideoId: parsed.currentVideoId,
                    sequenceVideoIds: parsed.sequenceVideoIds,
                    sequenceParamsDecoded: parsed.sequenceParamsDecoded,
                    sequenceParamsHash: parsed.sequenceParamsHash,
                    sequenceParamsLength: parsed.sequenceParamsLength,
                    sequenceParamsError: parsed.sequenceParamsError,
                    continuationHash: parsed.continuationHash,
                    requestContextHash: parsed.requestContextHash,
                    clientName: parsed.clientName,
                    clientVersion: parsed.clientVersion,
                    bodyStructureHash: parsed.bodyStructureHash,
                    warnings: parsed.warnings
                  }
                });
              } else {
                NetworkObserver.lastRequestVideoId = parsed.videoId || NetworkObserver.lastRequestVideoId;
                postMessage({
                  type: 'network_video_request',
                  data: {
                    pageUrl: location.href,
                    requestUrl: url,
                    correlationId: correlation,
                    requestKind: kind,
                    videoId: parsed.videoId
                  }
                });
              }
              for (var i = 0; i < parsed.warnings.length; i++) {
                NetworkObserver.warn(parsed.warnings[i], '');
              }
            },

            onSequenceResponse: function (text, url, correlation) {
              var ts = Date.now();
              NetworkObserver.lastSequenceResponseAt = ts;
              var parsed = parseSequenceResponse(text);
              NetworkObserver.lastSequenceVideoCount = parsed.items.length;
              NetworkObserver.lastSequenceParseStatus = parsed.parseStatus;
              postMessage({
                type: 'network_sequence_response',
                data: {
                  pageUrl: location.href,
                  correlationId: correlation,
                  currentVideoId: parsed.currentVideoId,
                  sequenceHash: parsed.sequenceHash,
                  continuationHash: parsed.continuationHash,
                  trackingHash: parsed.trackingHash,
                  responseContextHash: parsed.responseContextHash,
                  parserVersion: NETWORK_PARSER_VERSION,
                  parseStatus: parsed.parseStatus,
                  detectedShape: parsed.detectedShape,
                  warnings: parsed.warnings,
                  items: parsed.items
                }
              });
              for (var j = 0; j < parsed.warnings.length; j++) {
                NetworkObserver.warn(parsed.warnings[j], '');
              }
            },

            publishStatus: function (domVideoCount) {
              postMessage({
                type: 'network_observer_status',
                data: {
                  pageUrl: location.href,
                  firstSequenceRequestAt: NetworkObserver.firstSequenceRequestAt,
                  lastSequenceRequestAt: NetworkObserver.lastSequenceRequestAt,
                  lastSequenceResponseAt: NetworkObserver.lastSequenceResponseAt,
                  lastSequenceVideoCount: NetworkObserver.lastSequenceVideoCount,
                  lastSequenceParseStatus: NetworkObserver.lastSequenceParseStatus,
                  missedInitialPossible: NetworkObserver.missedInitialPossible,
                  lastRequestVideoId: NetworkObserver.lastRequestVideoId,
                  warningCount: NetworkObserver.warnings.length,
                  domVideoCount: domVideoCount || 0,
                  domListHash: lastKeysHash
                }
              });
            },

            install: function () {
              if (NetworkObserver.installed) { return; }
              NetworkObserver.installed = true;
              NetworkObserver.installedAt = Date.now();
              var wrappedFetch = false;
              var wrappedXhr = false;

              // fetch: 원본을 먼저 호출하고, 분석은 응답 복제본에서만 수행한다.
              try {
                var origFetch = window.fetch;
                if (typeof origFetch === 'function') {
                  window.fetch = function (input, init) {
                    var url = '';
                    var bodyText = '';
                    var kind = 'other';
                    var correlation = '';
                    try {
                      url = typeof input === 'string' ? input : (input && input.url) || '';
                      bodyText = (init && typeof init.body === 'string') ? init.body : '';
                      kind = classifyRequest(url);
                      if (kind !== 'other') {
                        correlation = NetworkObserver.newCorrelation();
                        NetworkObserver.onRequestSeen(kind, url, bodyText, correlation);
                      }
                    } catch (e) { /* 분석 실패가 원본 fetch를 막지 않는다 */ }
                    var result = origFetch.apply(this, arguments);
                    if (kind === 'reel_watch_sequence' && result && typeof result.then === 'function') {
                      try {
                        result = result.then(function (response) {
                          try {
                            if (response && typeof response.clone === 'function') {
                              var clone = response.clone();
                              if (clone && typeof clone.text === 'function') {
                                clone.text().then(function (text) {
                                  NetworkObserver.onSequenceResponse(text, url, correlation);
                                }).catch(function () {
                                  NetworkObserver.warn('response_read_failed', '');
                                });
                              }
                            }
                          } catch (e) {
                            NetworkObserver.warn('fetch_analysis_failed', '');
                          }
                          return response;
                        });
                      } catch (e) {
                        NetworkObserver.warn('fetch_analysis_failed', '');
                      }
                    }
                    return result;
                  };
                  wrappedFetch = true;
                }
              } catch (e) {
                NetworkObserver.warn('fetch_hook_failed', '');
              }

              // XMLHttpRequest: open/send를 감싸고, 분석은 addEventListener로만 추가한다.
              try {
                if (window.XMLHttpRequest && XMLHttpRequest.prototype) {
                  var origOpen = XMLHttpRequest.prototype.open;
                  var origSend = XMLHttpRequest.prototype.send;
                  XMLHttpRequest.prototype.open = function (method, url) {
                    try { this.__smUrl = String(url || ''); } catch (e) {}
                    return origOpen.apply(this, arguments);
                  };
                  XMLHttpRequest.prototype.send = function (body) {
                    var self = this;
                    var url = '';
                    try { url = self.__smUrl || ''; } catch (e) {}
                    var kind = classifyRequest(url);
                    var correlation = NetworkObserver.newCorrelation();
                    if (kind !== 'other') {
                      var bodyText = typeof body === 'string' ? body : '';
                      try {
                        NetworkObserver.onRequestSeen(kind, url, bodyText, correlation);
                      } catch (e) { /* 분석 실패가 원본 send를 막지 않는다 */ }
                    }
                    if (kind === 'reel_watch_sequence') {
                      try {
                        if (typeof self.addEventListener === 'function') {
                          self.addEventListener('load', function () {
                            try {
                              if (self.responseType === '' || self.responseType === 'text') {
                                NetworkObserver.onSequenceResponse(String(self.responseText || ''), url, correlation);
                              }
                            } catch (e) {
                              NetworkObserver.warn('xhr_analysis_failed', '');
                            }
                          });
                        }
                      } catch (e) {
                        NetworkObserver.warn('xhr_hook_failed', '');
                      }
                    }
                    return origSend.apply(this, arguments);
                  };
                  wrappedXhr = true;
                }
              } catch (e) {
                NetworkObserver.warn('xhr_hook_failed', '');
              }

              postMessage({
                type: 'network_observer_ready',
                data: {
                  pageUrl: location.href,
                  installedAt: NetworkObserver.installedAt,
                  observerVersion: VERSION,
                  parserVersion: NETWORK_PARSER_VERSION,
                  fetchWrapped: wrappedFetch,
                  xhrWrapped: wrappedXhr
                }
              });
            }
          };

          // ===== 초기화 / 재시작 =====
          function stop() {
            if (feedObserver) { try { feedObserver.disconnect(); } catch (e) {} feedObserver = null; }
            if (heartbeatTimer) { clearInterval(heartbeatTimer); heartbeatTimer = null; }
            if (urlTimer) { clearInterval(urlTimer); urlTimer = null; }
            if (snapshotTimer) { clearTimeout(snapshotTimer); snapshotTimer = null; }
            if (activeTimer) { clearTimeout(activeTimer); activeTimer = null; }
          }

          function shortVideoIdFromUrl(href) {
            var m = href.match(/\/shorts\/([A-Za-z0-9_-]{6,})/);
            return m ? m[1] : '';
          }

          function init() {
            lastKeys = null;
            lastKeysHash = '';
            currentActiveKey = '';
            revision = 0;
            lastContainer = null;
            var nav = null;
            try { nav = performance.getEntriesByType('navigation')[0]; } catch (e) {}
            var hint = (nav && nav.type === 'reload') ? 'full_reload' : 'initial';
            // 네트워크 관찰기는 DOM 관찰보다 먼저 설치해 초기 시퀀스 요청을 놓치지 않게 한다.
            NetworkObserver.install();
            startObservers();
            startUrlWatch();
            startHeartbeat();
            publishReady(hint);
          }

          window.${'$'}RESTART_FUNCTION = function () {
            stop();
            init();
          };

          // 파서 진단 핸들 (고정 데이터 테스트·진단에 사용). 민감정보를 노출하지 않는다.
          window.__shortsMonitorParser = {
            parseSequenceResponse: parseSequenceResponse,
            decodeSequenceParams: decodeSequenceParams,
            parseRequestBody: parseRequestBody,
            classifyRequest: classifyRequest,
            classifyUrlChange: classifyUrlChange,
            hashOf: hashOf
          };

          init();
        })();
    """.trimIndent()
        .replace("${'$'}GUARD_FLAG", GUARD_FLAG)
        .replace("${'$'}RESTART_FUNCTION", RESTART_FUNCTION)
        .replace("${'$'}ADAPTER_VERSION", ShortsDomAdapter.VERSION)
        .replace("${'$'}OBSERVER_VERSION", OBSERVER_VERSION)
        .replace("${'$'}NETWORK_PARSER_VERSION", NETWORK_PARSER_VERSION)
        .replace("${'$'}BRIDGE_OBJECT_NAME", BRIDGE_OBJECT_NAME)
        .replace("${'$'}HEARTBEAT_INTERVAL", HEARTBEAT_INTERVAL_MS.toString())
        .replace("${'$'}SNAPSHOT_DEBOUNCE", SNAPSHOT_DEBOUNCE_MS.toString())
        .replace("${'$'}MAX_RESPONSE_CHARS", MAX_RESPONSE_CHARS.toString())
        .replace("${'$'}MAX_SEQUENCE_ITEMS", MAX_SEQUENCE_ITEMS.toString())
}
