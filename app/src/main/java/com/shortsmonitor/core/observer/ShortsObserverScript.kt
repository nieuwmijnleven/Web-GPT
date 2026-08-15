package com.shortsmonitor.core.observer

/**
 * WebView에 주입하는 JavaScript 관찰기.
 *
 * 페이지 내부에서 MutationObserver로 다음 변화를 감지한다.
 * - 쇼츠 항목 추가 / 제거 / 순서 변경
 * - 현재 활성 영상 변경
 * - 쇼츠 컨테이너 재생성
 * - 주소 변경 (SPA 내부 탐색 포함)
 * - 페이지 전체 재로드
 *
 * 메시지는 `window.shortsMonitorBridge.postMessage(json)`로 네이티브에 전달하며,
 * 네이티브 쪽 허용 출처 규칙([ObserverBridge.ALLOWED_ORIGIN_RULES])이
 * 유튜브 외 출처의 호출을 차단한다.
 *
 * 같은 영상의 재렌더링은 안정 식별 키가 같으면 중복 스냅샷을 보내지 않는다.
 * 하트비트를 주기적으로 보내 네이티브가 관찰기 중단을 감지하고 재시작할 수 있게 한다.
 */
object ShortsObserverScript {

    /** addWebMessageListener로 등록하는 네이티브 통신 객체 이름. */
    const val BRIDGE_OBJECT_NAME = "shortsMonitorBridge"

    /** 중복 주입 방지 플래그. */
    const val GUARD_FLAG = "__shortsMonitorInjected"

    /** 네이티브가 재시작을 요청할 때 호출하는 전역 함수 이름. */
    const val RESTART_FUNCTION = "__shortsMonitorRestart"

    const val OBSERVER_VERSION = "1.0.0"

    /** 하트비트 주기. */
    const val HEARTBEAT_INTERVAL_MS = 5_000L

    /** 스냅샷 디바운스 시간. */
    const val SNAPSHOT_DEBOUNCE_MS = 200L

    val script: String = """
        (function () {
          if (window.${'$'}GUARD_FLAG) { return; }
          window.${'$'}GUARD_FLAG = true;

          var VERSION = '${'$'}OBSERVER_VERSION';
          var BRIDGE_NAME = '${'$'}BRIDGE_OBJECT_NAME';
          var HEARTBEAT_INTERVAL = ${'$'}HEARTBEAT_INTERVAL;
          var SNAPSHOT_DEBOUNCE = ${'$'}SNAPSHOT_DEBOUNCE;

          // ===== 유튜브 DOM 선택자 중앙 관리 =====
          // 유튜브 DOM 변경 시 이 블록만 수정하면 된다.
          var SELECTORS = {
            feed: [
              'ytm-shorts',
              'ytd-shorts',
              '#shorts-container',
              'ytd-reel-video-renderer'
            ],
            item: [
              'ytm-shorts-video-renderer',
              'ytd-reel-video-renderer',
              'a[href*="/shorts/"]'
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
              for (var i = 0; i < SELECTORS.feed.length; i++) {
                var el = document.querySelector(SELECTORS.feed[i]);
                if (el) { return el; }
              }
              var first = ShortsDomAdapter.findShortItems(document);
              return (first && first.length > 0) ? first[0].parentElement : null;
            },

            findShortItems: function (container) {
              var items = [];
              for (var i = 0; i < SELECTORS.item.length; i++) {
                var found = container.querySelectorAll(SELECTORS.item[i]);
                for (var j = 0; j < found.length; j++) {
                  if (items.indexOf(found[j]) < 0) { items.push(found[j]); }
                }
              }
              return items;
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

            detectActiveItem: function (items) {
              for (var i = 0; i < items.length; i++) {
                if (items[i].hasAttribute('is-active')) { return { item: items[i], index: i }; }
              }
              var active = document.activeElement;
              if (active) {
                for (var j = 0; j < items.length; j++) {
                  if (items[j] === active || items[j].contains(active)) {
                    return { item: items[j], index: j };
                  }
                }
              }
              if (items.length === 1) { return { item: items[0], index: 0 }; }
              return null;
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

          function hashOf(text) {
            var h = 5381;
            for (var i = 0; i < text.length; i++) {
              h = ((h << 5) + h + text.charCodeAt(i)) | 0;
            }
            return (h >>> 0).toString(36);
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
            var domItems = ShortsDomAdapter.findShortItems(feed);
            if (domItems.length === 0) { return null; }
            var shorts = [];
            var seen = {};
            for (var i = 0; i < domItems.length; i++) {
              var s = buildShort(domItems[i]);
              if (!s.identityKey || seen[s.identityKey]) { continue; }
              seen[s.identityKey] = true;
              shorts.push(s);
            }
            var active = ShortsDomAdapter.detectActiveItem(domItems);
            return {
              shorts: shorts,
              activeId: active ? ShortsDomAdapter.extractVideoId(active.item) : '',
              activeIndex: active ? active.index : -1
            };
          }

          // ===== 상태 =====
          var seq = 0;
          var revision = 0;
          var lastKeys = null;
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

          function publishSnapshot(hint) {
            var snapshot = buildSnapshot();
            if (!snapshot) { return; }
            var keys = keysOf(snapshot.shorts);
            // 같은 영상의 재렌더링: 안정 키가 같으면 중복 스냅샷을 보내지 않는다.
            if (sameKeys(keys, lastKeys)) {
              checkActiveChange(snapshot);
              return;
            }
            var reason = classifyChange(lastKeys, keys);
            if (hint === 'full_reload') { reason = 'full_reload'; }
            if (hint === 'navigation') { reason = 'navigation'; }
            lastKeys = keys;
            revision++;
            postMessage({
              type: 'list_snapshot',
              data: {
                revision: revision,
                reason: reason,
                url: location.href,
                shorts: snapshot.shorts
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
                count: snapshot.shorts.length
              }
            });
          }

          function publishPageInfo() {
            var snapshot = buildSnapshot();
            postMessage({
              type: 'page_info',
              data: {
                url: location.href,
                title: document.title || '',
                activeVideoId: snapshot ? snapshot.activeId : ''
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
            publishPageInfo();
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
              currentActiveKey = '';
              lastContainer = current;
              startObservers();
              postMessage({ type: 'dom_rebuilt', data: { revision: revision } });
              scheduleSnapshot();
            }
          }

          function onUrlChanged() {
            lastHref = location.href;
            lastKeys = null;
            currentActiveKey = '';
            revision = 0;
            publishPageInfo();
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
                  observerVersion: VERSION
                }
              });
            }, HEARTBEAT_INTERVAL);
          }

          // ===== 초기화 / 재시작 =====
          function stop() {
            if (feedObserver) { try { feedObserver.disconnect(); } catch (e) {} feedObserver = null; }
            if (heartbeatTimer) { clearInterval(heartbeatTimer); heartbeatTimer = null; }
            if (urlTimer) { clearInterval(urlTimer); urlTimer = null; }
            if (snapshotTimer) { clearTimeout(snapshotTimer); snapshotTimer = null; }
            if (activeTimer) { clearTimeout(activeTimer); activeTimer = null; }
          }

          function init() {
            lastKeys = null;
            currentActiveKey = '';
            revision = 0;
            lastContainer = null;
            var nav = null;
            try { nav = performance.getEntriesByType('navigation')[0]; } catch (e) {}
            var hint = (nav && nav.type === 'reload') ? 'full_reload' : 'initial';
            startObservers();
            startUrlWatch();
            startHeartbeat();
            publishReady(hint);
          }

          window.${'$'}RESTART_FUNCTION = function () {
            stop();
            init();
          };

          init();
        })();
    """.trimIndent()
        .replace("${'$'}GUARD_FLAG", GUARD_FLAG)
        .replace("${'$'}RESTART_FUNCTION", RESTART_FUNCTION)
        .replace("${'$'}ADAPTER_VERSION", ShortsDomAdapter.VERSION)
        .replace("${'$'}OBSERVER_VERSION", OBSERVER_VERSION)
        .replace("${'$'}BRIDGE_OBJECT_NAME", BRIDGE_OBJECT_NAME)
        .replace("${'$'}HEARTBEAT_INTERVAL", HEARTBEAT_INTERVAL_MS.toString())
        .replace("${'$'}SNAPSHOT_DEBOUNCE", SNAPSHOT_DEBOUNCE_MS.toString())
}
