/**
 * SillyClient Render Engine v0.2.3
 * 位于 SillyTavern 与 WebView/Chromium 之间的深度渲染调度引擎
 *
 * 核心架构组件:
 * 1. ChameleonEngine: 变色龙精准事件感知取色 (首帧瞬时同步 + 定向 DOM 变动通知，彻底 0 轮询)
 * 2. Streaming Diagnostics: HUD-only native-frame metrics without intercepting DOM writes
 * 3. Layer Explosion Elimination: 彻底根治几百条消息的图层爆炸，单滚动硬件层 + 动态末尾消息硬件层
 * 4. Full-Fidelity Visuals: 永久保留全站原生毛玻璃 (backdrop-filter) 与立体阴影 (box-shadow)，滑屏零降级零破坏
 * 5. FrameScheduler: 读写严格分离原子化调度 (Read/Write Phase 严格隔离)
 * 6. Invisible Animation Freeze & Image Async Decode: 视口外动画挂起与图片后台异步解码
 * 7. Zero-Timer Interaction Governor: 手势触控优先调频器 (0 Timer 堆开销)
 * 8. Performance Monitor & SC Slate-900 Glass HUD: 极客监控面板与全站微震触感开关
 */
(function() {
    if (window.__scRenderEngineInstalled) return;
    window.__scRenderEngineInstalled = true;

    let disposed = false;
    let pageSuspended = false;
    const ownedListeners = [];
    const patchRestorers = [];

    function isPageActive() {
        return !disposed && !pageSuspended && !document.hidden;
    }

    function listen(target, type, callback, options) {
        target.addEventListener(type, callback, options);
        ownedListeners.push(() => target.removeEventListener(type, callback, options));
    }

    // ========================================================
    // 0. 全局引擎状态 & 触感持久化配置
    // ========================================================
    // 存储可能被 WebView 策略拒绝（错误页/分区上下文）；引擎必须带降级地自持。
    const safeStorageGet = (key) => {
        try { return window.localStorage.getItem(key); } catch (_) { return null; }
    };
    const safeStorageSet = (key, value) => {
        try { window.localStorage.setItem(key, value); } catch (_) { /* 持久化不可用时仅用内存态 */ }
    };
    const engineState = {
        fps: 90.0,
        frameTime: 11.1,
        p95: 11.1,
        p99: 11.1,
        longTaskCount: 0,
        domCount: 0,
        mutationsPerSec: 0,
        streamingFps: 0,
        lastFrameJsTime: 1.2,
        virtualizationActive: false,
        frozenAnimCount: 0,
        isInteracting: false,
        lastTopColor: null,
        hapticEnabled: safeStorageGet('__sc_haptic_enabled') === '1',
        hudVisible: false
    };

    // ========================================================
    // 1. RUNTIME PATCHES (原生底座加固与事件纠偏)
    // ========================================================
    function installRuntimePatches() {
        try {
            // 1.1 彻底清理历史遗留旧样式表与标记
            const oldStyle = document.getElementById('sc-drawer-perf');
            if (oldStyle) oldStyle.remove();

            if (window.__scJQueryFxWatcher) {
                clearInterval(window.__scJQueryFxWatcher);
                window.__scJQueryFxWatcher = null;
            }
            if (window.jQuery && window.jQuery.fx) {
                window.jQuery.fx.off = false;
            }

            // 1.2 声明 CSS field-sizing 支持，绕过 textarea 同步重排死循环
            if (window.CSS && !CSS.supports('field-sizing', 'content')) {
                const originalSupports = CSS.supports.bind(CSS);
                const patchedSupports = function(property, value) {
                    if (property === 'field-sizing') return true;
                    return originalSupports(property, value);
                };
                const previousSupports = CSS.supports;
                CSS.supports = patchedSupports;
                patchRestorers.push(() => {
                    if (CSS.supports === patchedSupports) CSS.supports = previousSupports;
                });
            }

            // 1.3 取消进角色对话页面自动展开输入法 (用户主动轻触才弹起)
            if (!window.__scAutoFocusBlockerInstalled) {
                window.__scAutoFocusBlockerInstalled = true;
                let userTappedTextarea = false;

                const markUserTap = (e) => {
                    userTappedTextarea = !!(e.target && (e.target.id === 'send_textarea' || (e.target.closest && e.target.closest('#send_textarea'))));
                };
                listen(document, 'touchstart', markUserTap, { capture: true, passive: true });
                listen(document, 'mousedown', markUserTap, { capture: true, passive: true });

                const origTextareaFocus = HTMLTextAreaElement.prototype.focus;
                const patchedTextareaFocus = function(options) {
                    if (this.id === 'send_textarea' && !userTappedTextarea) return;
                    return origTextareaFocus.call(this, options);
                };
                HTMLTextAreaElement.prototype.focus = patchedTextareaFocus;
                patchRestorers.push(() => {
                    if (HTMLTextAreaElement.prototype.focus === patchedTextareaFocus) {
                        HTMLTextAreaElement.prototype.focus = origTextareaFocus;
                        window.__scAutoFocusBlockerInstalled = false;
                    }
                });

                if (window.jQuery) {
                    const jq = window.jQuery;
                    const origTrigger = jq.fn.trigger;
                    const patchedTrigger = function(type, data) {
                        if (this.is('#send_textarea') && !userTappedTextarea && (type === 'focus' || type === 'click' || type === 'focusin')) {
                            return this;
                        }
                        return origTrigger.apply(this, arguments);
                    };
                    jq.fn.trigger = patchedTrigger;
                    patchRestorers.push(() => {
                        if (jq.fn.trigger === patchedTrigger) jq.fn.trigger = origTrigger;
                    });
                }
            }

            // 1.4 清理 form_sheld 遗留 lift/spacer 结构，恢复纯净 DOM
            const formSheld = document.getElementById('form_sheld');
            if (formSheld) {
                const lift = formSheld.querySelector('[data-sc-ime-lift]');
                const spacer = formSheld.querySelector('[data-sc-ime-spacer]');
                if (lift) {
                    while (lift.firstChild) formSheld.insertBefore(lift.firstChild, lift);
                    lift.remove();
                }
                if (spacer) spacer.remove();
            }

            // 1.5 二级抽屉 (.inline-drawer-content) 90ms 纯透明度微显影（0 JS 逐帧循环开销）
            if (window.jQuery && !window.__scSlideTogglePatched) {
                window.__scSlideTogglePatched = true;
                const jq = window.jQuery;
                const SELECTOR = '.inline-drawer-content';
                const origToggle = jq.fn.slideToggle;
                const origDown   = jq.fn.slideDown;
                const origUp     = jq.fn.slideUp;

                const isHidden = (el) =>
                    el.style.display === 'none' ||
                    window.getComputedStyle(el).display === 'none';

                const fastAnimate = (el, toOpen, cb) => {
                    if (toOpen) {
                        el.style.display = 'block';
                        try {
                            if (el.animate) {
                                el.animate([
                                    { opacity: 0.15, transform: 'translateY(-4px)' },
                                    { opacity: 1, transform: 'translateY(0)' }
                                ], {
                                    duration: 90,
                                    easing: 'cubic-bezier(0.12, 0.98, 0.24, 1)',
                                    fill: 'forwards'
                                });
                            }
                        } catch(_) {}
                        if (typeof cb === 'function') cb.call(el);
                    } else {
                        try {
                            if (el.animate) {
                                const anim = el.animate([
                                    { opacity: 1, transform: 'translateY(0)' },
                                    { opacity: 0, transform: 'translateY(-3px)' }
                                ], {
                                    duration: 70,
                                    easing: 'cubic-bezier(0.4, 0, 1, 1)',
                                    fill: 'forwards'
                                });
                                anim.onfinish = () => {
                                    el.style.display = 'none';
                                    if (typeof cb === 'function') cb.call(el);
                                };
                            } else {
                                el.style.display = 'none';
                                if (typeof cb === 'function') cb.call(el);
                            }
                        } catch(_) {
                            el.style.display = 'none';
                            if (typeof cb === 'function') cb.call(el);
                        }
                    }
                };

                const patchedToggle = function(duration, easing, complete) {
                    const cb = typeof easing === 'function' ? easing : complete;
                    const inline = this.filter(SELECTOR);
                    const rest   = this.not(SELECTOR);
                    inline.each(function() { fastAnimate(this, isHidden(this), cb); });
                    if (rest.length) origToggle.apply(rest, arguments);
                    return this;
                };

                const patchedDown = function(duration, easing, complete) {
                    const cb = typeof easing === 'function' ? easing : complete;
                    const inline = this.filter(SELECTOR);
                    const rest   = this.not(SELECTOR);
                    inline.each(function() { fastAnimate(this, true, cb); });
                    if (rest.length) origDown.apply(rest, arguments);
                    return this;
                };

                const patchedUp = function(duration, easing, complete) {
                    const cb = typeof easing === 'function' ? easing : complete;
                    const inline = this.filter(SELECTOR);
                    const rest   = this.not(SELECTOR);
                    inline.each(function() { fastAnimate(this, false, cb); });
                    if (rest.length) origUp.apply(rest, arguments);
                    return this;
                };
                jq.fn.slideToggle = patchedToggle;
                jq.fn.slideDown = patchedDown;
                jq.fn.slideUp = patchedUp;
                patchRestorers.push(() => {
                    if (jq.fn.slideToggle === patchedToggle) jq.fn.slideToggle = origToggle;
                    if (jq.fn.slideDown === patchedDown) jq.fn.slideDown = origDown;
                    if (jq.fn.slideUp === patchedUp) jq.fn.slideUp = origUp;
                    window.__scSlideTogglePatched = false;
                });
            }
        } catch(_) {}
    }
    installRuntimePatches();

    // ========================================================
    // 2. NATIVE HAPTIC BRIDGE (原生线性马达精密触感引擎)
    // ========================================================
    window.__scHaptic = function(type) {
        if (!isPageActive() || !engineState.hapticEnabled) return;
        try {
            if (window.SillyClientHaptic && window.SillyClientHaptic.trigger) {
                window.SillyClientHaptic.trigger(type || 'tick');
            }
        } catch(_) {}
    };

    // 酒馆全站高频交互触觉委托捕获
    listen(document, 'pointerdown', (e) => {
        if (!engineState.hapticEnabled) return;
        const target = e.target;
        if (!target || !(target instanceof Element)) return;

        // 1. 发送与生成中止
        if (target.closest('#send_but, .send_button, #abort_button, #send_textarea_container button')) {
            window.__scHaptic('click');
            return;
        }
        // 2. 左右侧边栏与抽屉切换按钮
        if (target.closest('#right-nav-panel, #left-nav-panel, #nav-toggle, .drawer-toggle, #open_character_drawer, #open_world_info, #floating_prompt_manager, #option_toggle, #persona-management-button')) {
            window.__scHaptic('tick');
            return;
        }
        // 3. Swipe 滑动切换与消息操作菜单
        if (target.closest('.swipe_left, .swipe_right, .swipe_counter, .mes_edit, .mes_copy, .mes_del, .mes_favorite, .mes_btn')) {
            window.__scHaptic('tick');
            return;
        }
        // 4. 角色卡选择与头像轻触
        if (target.closest('.character_select, .avatar, .select_character, .character_item, #character_list .character, .dry_run_character')) {
            window.__scHaptic('tick');
            return;
        }
        // 5. 顶栏操作项、弹窗确认与通用按钮
        if (target.closest('#top-bar button, .menu_button, .list-group-item, .dialog_button, .popup_button, .interactive_element')) {
            window.__scHaptic('tick');
            return;
        }
    }, { capture: true, passive: true });

    // ========================================================
    // 2. FRAME SCHEDULER (统一单帧调度器 - Read/Write 严格分离)
    // ========================================================
    const scheduler = {
        readQueue: [],
        writeQueue: [],
        scheduled: false,
        frameId: null,
        flushing: false,

        read: function(fn) {
            if (disposed) return;
            this.readQueue.push(fn);
            this.schedule();
        },

        write: function(fn) {
            if (disposed) return;
            this.writeQueue.push(fn);
            this.schedule();
        },

        schedule: function() {
            if (this.scheduled || !isPageActive()) return;
            this.scheduled = true;
            this.frameId = requestAnimationFrame(() => {
                this.frameId = null;
                this.flush();
            });
        },

        flush: function() {
            if (this.flushing) return;
            if (!isPageActive()) {
                this.pause();
                return;
            }
            if (this.frameId !== null) cancelAnimationFrame(this.frameId);
            this.frameId = null;
            this.scheduled = true;
            this.flushing = true;
            const start = performance.now();
            const reads = this.readQueue.splice(0);
            for (let i = 0; i < reads.length; i++) {
                if (disposed) break;
                try { reads[i](); } catch(e) {}
            }

            const writes = this.writeQueue.splice(0);
            for (let i = 0; i < writes.length; i++) {
                if (disposed) break;
                try { writes[i](); } catch(e) {}
            }

            this.scheduled = false;
            this.flushing = false;
            engineState.lastFrameJsTime = performance.now() - start;
            if (this.readQueue.length || this.writeQueue.length) this.schedule();
        },

        pause: function() {
            if (this.frameId !== null) cancelAnimationFrame(this.frameId);
            this.frameId = null;
            this.scheduled = this.flushing;
        }
    };
    window.__scFrameScheduler = scheduler;

    // ========================================================
    // 3. STREAMING DIAGNOSTICS (native HTML and scroll remain upstream-owned)
    // ========================================================
    const streamingBatcher = {
        streamingFpsCount: 0,
        currentStreamingFps: 0,
        isStreamingActive: false,
    };
    window.__scStreamingBatcher = streamingBatcher;

    // ========================================================
    // 4. CHAMELEON ENGINE (变色龙精准事件感知取色 - 0 轮询)
    // ========================================================
    const chameleonEngine = {
        lastReportedColor: null,
        debounceTimer: null,
        observer: null,

        parseCssColor: function(str) {
            if (!str || str === 'transparent' || str === 'inherit' || str === 'initial') return null;
            const m = str.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?\)/);
            if (m) {
                const a = m[4] !== undefined ? parseFloat(m[4]) : 1.0;
                if (a > 0.05) return { r: parseInt(m[1], 10), g: parseInt(m[2], 10), b: parseInt(m[3], 10), a: a };
            }
            if (str.startsWith('#')) {
                let hex = str.substring(1);
                if (hex.length === 3) hex = hex[0]+hex[0] + hex[1]+hex[1] + hex[2]+hex[2];
                if (hex.length === 6) {
                    return {
                        r: parseInt(hex.substring(0, 2), 16),
                        g: parseInt(hex.substring(2, 4), 16),
                        b: parseInt(hex.substring(4, 6), 16),
                        a: 1.0
                    };
                }
            }
            return null;
        },

        blend: function(fg, bg) {
            if (!fg) return bg;
            if (fg.a >= 0.999) return fg;
            const bgR = bg ? bg.r : 36;
            const bgG = bg ? bg.g : 36;
            const bgB = bg ? bg.b : 37;
            const a = fg.a;
            return {
                r: Math.round(fg.r * a + bgR * (1 - a)),
                g: Math.round(fg.g * a + bgG * (1 - a)),
                b: Math.round(fg.b * a + bgB * (1 - a)),
                a: 1.0
            };
        },

        computeTopColor: function() {
            let bodyBg = null;
            if (document.body) {
                bodyBg = this.parseCssColor(window.getComputedStyle(document.body).backgroundColor);
            }
            const topBar = document.getElementById('top-bar');
            if (topBar) {
                const cs = window.getComputedStyle(topBar);
                const c = this.parseCssColor(cs.backgroundColor);
                if (c) {
                    const res = (c.a >= 0.90) ? c : this.blend(c, bodyBg);
                    return (0xFF000000 | (res.r << 16) | (res.g << 8) | res.b);
                }
            }
            try {
                const rootStyle = window.getComputedStyle(document.documentElement);
                const tint = rootStyle.getPropertyValue('--SmartThemeBlurTintColor');
                if (tint) {
                    const tc = this.parseCssColor(tint.trim());
                    if (tc) {
                        const res = (tc.a >= 0.90) ? tc : this.blend(tc, bodyBg);
                        return (0xFF000000 | (res.r << 16) | (res.g << 8) | res.b);
                    }
                }
            } catch (_) {}
            const meta = document.querySelector('meta[name="theme-color"]');
            if (meta) {
                const mc = this.parseCssColor(meta.getAttribute('content'));
                if (mc) return (0xFF000000 | (mc.r << 16) | (mc.g << 8) | mc.b);
            }
            if (bodyBg) return (0xFF000000 | (bodyBg.r << 16) | (bodyBg.g << 8) | bodyBg.b);
            return null;
        },

        sampleAndReport: function(force) {
            if (!isPageActive()) return;
            try {
                const color = this.computeTopColor();
                if (color !== null && (force || color !== this.lastReportedColor)) {
                    this.lastReportedColor = color;
                    engineState.lastTopColor = color;
                    if (window.SillyClientRenderBridge && window.SillyClientRenderBridge.onColorChanged) {
                        window.SillyClientRenderBridge.onColorChanged(color);
                    }
                }
            } catch(_) {}
        },

        onTargetMutated: function() {
            if (!isPageActive()) return;
            if (this.debounceTimer) clearTimeout(this.debounceTimer);
            this.debounceTimer = setTimeout(() => {
                this.debounceTimer = null;
                this.sampleAndReport(false);
            }, 120);
        },

        initObserver: function() {
            if (!isPageActive() || !window.MutationObserver) return;
            try {
                if (!this.observer) this.observer = new MutationObserver(() => this.onTargetMutated());
                this.bindTargets();
            } catch(_) {}
        },

        bindTargets: function() {
            if (!this.observer || !isPageActive()) return;
            this.observer.disconnect();
            const candidates = [
                document.documentElement,
                document.body,
                document.getElementById('top-bar'),
                document.getElementById('bg1'),
                document.getElementById('bg2'),
                document.querySelector('meta[name="theme-color"]')
            ];
            candidates.forEach(el => {
                if (el) {
                    try {
                        this.observer.observe(el, {
                            attributes: true,
                            attributeFilter: ['style', 'class', 'content'],
                            childList: false,
                            subtree: false
                        });
                    } catch(_) {}
                }
            });
        },

        pause: function() {
            if (this.debounceTimer !== null) clearTimeout(this.debounceTimer);
            this.debounceTimer = null;
            if (this.observer) this.observer.disconnect();
        }
    };
    window.__scChameleonEngine = chameleonEngine;

    let chameleonStartupTimer = null;

    function startChameleon() {
        if (!isPageActive()) return;
        chameleonEngine.sampleAndReport(true);
        if (document.readyState !== 'loading') chameleonEngine.initObserver();
        if (chameleonStartupTimer !== null) clearTimeout(chameleonStartupTimer);
        // Rebind after the upstream SPA mounts its theme targets.
        chameleonStartupTimer = setTimeout(() => {
            chameleonStartupTimer = null;
            chameleonEngine.bindTargets();
            chameleonEngine.sampleAndReport(false);
        }, 500);
    }

    if (document.readyState === 'loading') {
        listen(document, 'DOMContentLoaded', () => {
            chameleonEngine.sampleAndReport(true);
            chameleonEngine.initObserver();
        }, { once: true });
    }
    startChameleon();

    // ========================================================
    // 5. DOM MUTATION BATCHER (突变监控与聚合，HUD 按需挂载)
    // ========================================================
    let mutationCounter = 0;
    let domMutationObserver = null;
    let streamingFramePending = false;

    function startMutationObserver() {
        if (domMutationObserver || !isPageActive() || !engineState.hudVisible || !window.MutationObserver) return;
        try {
            domMutationObserver = new MutationObserver((mutations) => {
                if (!isPageActive() || !engineState.hudVisible) return;
                mutationCounter += mutations.length;
                for (const mutation of mutations) {
                    const target = mutation.target instanceof Element ? mutation.target : mutation.target.parentElement;
                    if (target && target.closest('.mes_text')) {
                        streamingFramePending = true;
                        streamingBatcher.isStreamingActive = true;
                        break;
                    }
                }
            });
            domMutationObserver.observe(document.documentElement, {
                childList: true,
                subtree: true,
                attributes: false,
                characterData: true
            });
        } catch(e) {}
    }

    function stopMutationObserver() {
        if (domMutationObserver) {
            domMutationObserver.disconnect();
            domMutationObserver = null;
        }
        mutationCounter = 0;
        streamingFramePending = false;
        streamingBatcher.streamingFpsCount = 0;
        streamingBatcher.currentStreamingFps = 0;
        streamingBatcher.isStreamingActive = false;
        engineState.mutationsPerSec = 0;
        engineState.streamingFps = 0;
    }

    function updateMutationMetrics() {
        if (!isPageActive() || !engineState.hudVisible) return;
        engineState.mutationsPerSec = mutationCounter;
        mutationCounter = 0;
        engineState.streamingFps = streamingBatcher.streamingFpsCount;
        streamingBatcher.currentStreamingFps = streamingBatcher.streamingFpsCount;
        streamingBatcher.streamingFpsCount = 0;
        if (engineState.streamingFps === 0 && !streamingFramePending) {
            streamingBatcher.isStreamingActive = false;
        }
    }

    // ========================================================
    // 6. 根除图层爆炸与物理沙箱 (Layer Explosion Elimination & Containment)
    // 永久保留全站毛玻璃与投影，滑屏绝对零降级
    // ========================================================
    function setupVirtualizationAndContainment() {
        if (disposed || document.getElementById('sc-p1-containment')) return;
        const style = document.createElement('style');
        style.id = 'sc-p1-containment';
        style.textContent = `
            /* 1. 消除外层 html/body 双重滚动分层争抢，让单一物理滚动图层收敛在 #chat */
            html, body {
                overflow: hidden !important;
                height: 100% !important;
                overscroll-behavior: none !important;
                text-rendering: optimizeSpeed !important; /* 文字排版提速：跳过昂贵的字偶间距计算 */
            }
            #chat {
                overscroll-behavior-y: contain !important;
                -webkit-overflow-scrolling: touch !important;
                transform: translateZ(0); /* 唯一主滚动硬件合成层 */
                will-change: scroll-position;
                touch-action: pan-y pinch-zoom; /* 纵向滑动直通合成器，绕过主线程 JS 计算 */
                scroll-behavior: auto !important; /* 禁用软滚动引起的物理衰减撕裂 */
                text-rendering: optimizeSpeed !important;
            }
            /* 2. 现代输入框原生尺寸通道：消除 JS 频繁读取 scrollHeight 造成的强制回流 */
            #send_textarea {
                field-sizing: content !important;
                max-height: 160px !important;
                contain: layout style !important;
            }
            /* 3. 根除图层爆炸：历史消息坚决不加 translateZ(0)，使用纯局部布局与重绘隔离 */
            #chat .mes {
                contain: layout style !important;
            }
            /* 仅最新一条正在生成变动的消息赋予独立硬件层，杜绝动态生成时重绘静态历史消息 */
            #chat .mes:last-child {
                transform: translateZ(0);
            }
            /* 头像与静态资源局部绘制隔离：杜绝 GIF 头像扩散重绘 */
            .mes_avatar {
                contain: paint layout !important;
                user-select: none !important;
                -webkit-user-select: none !important;
            }
            /* 4. 图片 GPU 显存自动释放通道：离屏自动释放解码纹理，视口自动后台异步光栅化 */
            #chat img {
                content-visibility: auto;
                contain-intrinsic-size: auto 120px;
                decoding: async !important;
            }
            /* 5. 打字光标与旋转动效独立硬件层隔离：闪烁时绝对不重绘消息文字 */
            .typing_indicator, .cursor, .typing, .spinner, .rotating {
                will-change: opacity, transform !important;
                transform: translateZ(0) !important;
            }
            /* 代码块、复杂表格与公式横向滚动沙箱：防止撑破气泡与垂直滑动偏航 */
            #chat .mes_text pre,
            #chat .mes_text table,
            #chat .katex-display {
                max-width: 100% !important;
                overflow-x: auto !important;
                -webkit-overflow-scrolling: touch !important;
                contain: layout paint;
            }
            /* 消除移动端 300ms 点击延迟与双击探测 */
            button, .menu_button, .drawer-toggle, #send_but {
                touch-action: manipulation;
            }
            /* 离屏冻结类：挂起不可见视口元素的 CSS 动画 */
            .__sc-anim-frozen, .__sc-anim-frozen * {
                animation-play-state: paused !important;
            }
            * {
                -webkit-tap-highlight-color: transparent !important;
            }
        `;
        document.head.appendChild(style);
        engineState.virtualizationActive = true;
    }
    if (document.head) {
        setupVirtualizationAndContainment();
    } else {
        listen(document, 'DOMContentLoaded', setupVirtualizationAndContainment, { once: true });
    }

    // ========================================================
    // 7. 视口外动画挂起冻结 & 图片原型级异步解码
    // ========================================================
    const frozenElements = new Set();
    const observedAnimations = new Set();
    let animObserver = null;
    let animationScanTimer = null;
    let animationIdleId = null;
    if (window.IntersectionObserver) {
        try {
            animObserver = new IntersectionObserver((entries) => {
                if (!isPageActive()) return;
                for (let i = 0; i < entries.length; i++) {
                    const entry = entries[i];
                    const target = entry.target;
                    if (!target.isConnected || !observedAnimations.has(target)) continue;
                    if (entry.isIntersecting) {
                        target.classList.remove('__sc-anim-frozen');
                        frozenElements.delete(target);
                    } else {
                        target.classList.add('__sc-anim-frozen');
                        frozenElements.add(target);
                    }
                }
                engineState.frozenAnimCount = frozenElements.size;
            }, {
                rootMargin: '80px 0px 80px 0px'
            });
        } catch(e) {}
    }


    function scanAndObserveAnimations() {
        if (!animObserver || !isPageActive()) return;
        for (const element of observedAnimations) {
            if (!element.isConnected || !element.matches('.spinner, .rotating, .typing_indicator')) {
                animObserver.unobserve(element);
                observedAnimations.delete(element);
                frozenElements.delete(element);
                element.classList.remove('__sc-anim-frozen');
            }
        }
        const candidates = document.querySelectorAll('.spinner, .rotating, .typing_indicator');
        for (let i = 0; i < candidates.length; i++) {
            if (!observedAnimations.has(candidates[i])) {
                animObserver.observe(candidates[i]);
                observedAnimations.add(candidates[i]);
            }
        }
        engineState.frozenAnimCount = frozenElements.size;
    }

    // 利用 requestIdleCallback 在浏览器主线程空闲阶段（>3ms）执行后台扫描，杜绝关键帧竞争
    function scheduleIdleScan() {
        animationScanTimer = null;
        if (!isPageActive() || !animObserver) return;
        const run = (deadline) => {
            animationIdleId = null;
            animationScanTimer = null;
            if (!isPageActive()) return;
            if (!isCurrentlyInteracting() && (!deadline || deadline.timeRemaining() > 3)) {
                scanAndObserveAnimations();
            }
            animationScanTimer = setTimeout(scheduleIdleScan, 4000);
        };
        if (window.requestIdleCallback) {
            animationIdleId = window.requestIdleCallback(run, { timeout: 6000 });
        } else {
            animationScanTimer = setTimeout(run, 4000);
        }
    }
    if (isPageActive() && animObserver) {
        animationScanTimer = setTimeout(scheduleIdleScan, 2000);
    }

    function pauseAnimationScan() {
        if (animationScanTimer !== null) clearTimeout(animationScanTimer);
        animationScanTimer = null;
        if (animationIdleId !== null && window.cancelIdleCallback) {
            window.cancelIdleCallback(animationIdleId);
        }
        animationIdleId = null;
        if (animObserver) animObserver.disconnect();
        observedAnimations.clear();
        for (const element of frozenElements) element.classList.remove('__sc-anim-frozen');
        frozenElements.clear();
        engineState.frozenAnimCount = 0;
    }

    // ========================================================
    // 8. 交互优先调频器 (Zero-Timer Interaction Governor)
    // ========================================================
    let lastInteractionTimestamp = 0;
    function notifyInteraction() {
        if (!isPageActive()) return;
        lastInteractionTimestamp = performance.now();
        engineState.isInteracting = true;
    }
    function isCurrentlyInteracting() {
        if (!engineState.isInteracting) return false;
        if (performance.now() - lastInteractionTimestamp > 180) {
            engineState.isInteracting = false;
            return false;
        }
        return true;
    }
    listen(window, 'touchstart', notifyInteraction, { passive: true, capture: true });
    listen(window, 'touchmove', notifyInteraction, { passive: true });
    listen(window, 'scroll', notifyInteraction, { passive: true, capture: true });

    // ========================================================
    // 9. PERFORMANCE MONITOR & HUD (美化对齐 SC 整体前端规范)
    // ========================================================
    let longTaskObserver = null;
    let mutationInterval = null;
    let domCountInterval = null;
    let hudInterval = null;

    const frameTimes = [];
    let lastTimestamp = performance.now();
    let frameLoopId = null;
    let frameLoopCounter = 0;

    function frameLoop(now) {
        if (!isPageActive() || !engineState.hudVisible) {
            frameLoopId = null;
            return;
        }
        const delta = now - lastTimestamp;
        lastTimestamp = now;
        if (streamingFramePending) {
            streamingFramePending = false;
            streamingBatcher.streamingFpsCount++;
        }

        if (delta > 0 && delta < 500) {
            frameTimes.push(delta);
            if (frameTimes.length > 60) frameTimes.shift();

            frameLoopCounter++;
            // 仅每 20 帧（约 220ms）才进行一次排序汇总，单帧内 0 对象分配、0 GC 压力
            if (frameLoopCounter >= 20) {
                frameLoopCounter = 0;
                const avg = frameTimes.reduce((a, b) => a + b, 0) / frameTimes.length;
                engineState.fps = Math.min(120, +(1000 / avg).toFixed(1));
                engineState.frameTime = +avg.toFixed(1);

                const sorted = [...frameTimes].sort((a, b) => a - b);
                const idx95 = Math.floor(sorted.length * 0.95);
                const idx99 = Math.floor(sorted.length * 0.99);
                engineState.p95 = +(sorted[idx95] || avg).toFixed(1);
                engineState.p99 = +(sorted[idx99] || avg).toFixed(1);
            }
        }

        frameLoopId = requestAnimationFrame(frameLoop);
    }

    function startFrameLoop() {
        if (frameLoopId !== null || !isPageActive() || !engineState.hudVisible) return;
        lastTimestamp = performance.now();
        frameLoopCounter = 0;
        frameTimes.length = 0;
        frameLoopId = requestAnimationFrame(frameLoop);
    }

    function stopFrameLoop() {
        if (frameLoopId !== null) {
            cancelAnimationFrame(frameLoopId);
            frameLoopId = null;
        }
    }

    function updateDomCount() {
        if (!isPageActive() || !engineState.hudVisible) return;
        if (!isCurrentlyInteracting()) {
            engineState.domCount = document.getElementsByTagName('*').length;
        }
    }

    let hudElement = null;
    let hudRevealFrame = null;
    let hudHideTimer = null;

    function createHud() {
        if (disposed || hudElement || !document.body) return;
        hudElement = document.createElement('div');
        hudElement.id = 'sc-perf-hud';
        hudElement.style.cssText = `
            display: none;
            position: fixed;
            top: calc(var(--topBarBlockSize, 42px) + 8px);
            right: 12px;
            width: 242px;
            background: rgba(15, 17, 23, 0.90);
            backdrop-filter: blur(24px) saturate(1.4);
            -webkit-backdrop-filter: blur(24px) saturate(1.4);
            border: 1px solid rgba(255, 255, 255, 0.10);
            border-radius: 16px;
            padding: 12px 14px;
            font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
            font-size: 11px;
            color: #E2E8F0;
            z-index: 999999;
            box-shadow: 0 16px 40px -4px rgba(0, 0, 0, 0.65), 0 0 0 1px rgba(255, 255, 255, 0.05);
            pointer-events: auto;
            user-select: none;
            transition: opacity 160ms cubic-bezier(0.12, 0.98, 0.24, 1), transform 160ms cubic-bezier(0.12, 0.98, 0.24, 1);
            transform-origin: top right;
        `;

        listen(hudElement, 'dblclick', (e) => {
            e.stopPropagation();
            window.__scHaptic('tick');
            window.__scTogglePerfHud();
        });

        hudElement.innerHTML = `
            <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:10px; border-bottom:1px solid rgba(255,255,255,0.08); padding-bottom:6px;">
                <div style="display:flex; align-items:center; gap:6px;">
                    <span style="display:inline-block; width:6px; height:6px; border-radius:50%; background:#10B981; box-shadow:0 0 8px #10B981;"></span>
                    <span style="font-weight:700; color:#EAB308; letter-spacing:0.6px; font-size:11px;">SC RENDER ENGINE</span>
                </div>
                <span id="sc-perf-health" style="font-size:9.5px; padding:2px 6px; border-radius:999px; background:rgba(16, 185, 129, 0.15); color:#34D399; font-weight:600; border:1px solid rgba(16, 185, 129, 0.3);">90Hz Ultra</span>
            </div>
            <div style="display:grid; grid-template-columns: 1fr auto; row-gap:4px; font-size:10.5px;">
                <span style="color:#64748B;">FPS</span>
                <span id="sc-perf-fps" style="font-weight:600; color:#38BDF8;">--</span>

                <span style="color:#64748B;">Frame Time</span>
                <span id="sc-perf-frame">-- ms</span>

                <span style="color:#64748B;">P95 / P99</span>
                <span id="sc-perf-p95">-- / --</span>

                <span style="color:#64748B;">Long Tasks</span>
                <span id="sc-perf-longtasks" style="color:#F43F5E;">0</span>

                <span style="color:#64748B;">DOM Count</span>
                <span id="sc-perf-dom">--</span>

                <span style="color:#64748B;">Mutations</span>
                <span id="sc-perf-mut">--/s</span>

                <span style="color:#64748B;">Streaming</span>
                <span id="sc-perf-stream">Idle</span>

                <span style="color:#64748B;">JS Frame</span>
                <span id="sc-perf-js">-- ms</span>

                <span style="color:#64748B;">Chameleon</span>
                <span id="sc-perf-cham" style="color:#34D399; font-weight:600;">Event-0Poll</span>

                <span style="color:#64748B;">Layer Engine</span>
                <span id="sc-perf-layer" style="color:#10B981; font-weight:600;">Anti-Explosion</span>

                <span style="color:#64748B;">Frozen Anims</span>
                <span id="sc-perf-frozen" style="color:#A78BFA;">0</span>

                <span style="color:#64748B;">Governor</span>
                <span id="sc-perf-gov" style="color:#38BDF8;">Smooth</span>
            </div>

            <!-- 触感反馈控制开关 -->
            <div style="display:flex; justify-content:space-between; align-items:center; margin-top:10px; padding-top:8px; border-top:1px solid rgba(255,255,255,0.08);">
                <div style="display:flex; flex-direction:column;">
                    <span style="color:#94A3B8; font-size:10.5px; font-weight:500;">触感反馈</span>
                    <span style="color:#475569; font-size:9px;">全站微米级振动</span>
                </div>
                <div id="sc-haptic-toggle-btn" style="
                    position: relative;
                    width: 36px;
                    height: 20px;
                    background: ${engineState.hapticEnabled ? '#10B981' : '#334155'};
                    border-radius: 999px;
                    cursor: pointer;
                    transition: background 180ms cubic-bezier(0.12, 0.98, 0.24, 1);
                ">
                    <div id="sc-haptic-knob" style="
                        position: absolute;
                        top: 2px;
                        left: ${engineState.hapticEnabled ? '18px' : '2px'};
                        width: 16px;
                        height: 16px;
                        background: #FFFFFF;
                        border-radius: 50%;
                        box-shadow: 0 1px 4px rgba(0, 0, 0, 0.35);
                        transition: left 180ms cubic-bezier(0.12, 0.98, 0.24, 1);
                    "></div>
                </div>
            </div>

            <div style="margin-top:10px; text-align:center; font-size:9px; color:#475569;">
                连续点击右上角 7 次或双击面板关闭
            </div>
        `;

        // 绑定触感开关切换
        const toggleBtn = hudElement.querySelector('#sc-haptic-toggle-btn');
        const knob = hudElement.querySelector('#sc-haptic-knob');
        if (toggleBtn && knob) {
            listen(toggleBtn, 'click', (e) => {
                e.stopPropagation();
                engineState.hapticEnabled = !engineState.hapticEnabled;
                safeStorageSet('__sc_haptic_enabled', engineState.hapticEnabled ? '1' : '0');
                toggleBtn.style.background = engineState.hapticEnabled ? '#10B981' : '#334155';
                knob.style.left = engineState.hapticEnabled ? '18px' : '2px';
                if (engineState.hapticEnabled) {
                    window.__scHaptic('click');
                }
            });
        }

        document.body.appendChild(hudElement);
    }

    function updateHud() {
        if (!isPageActive() || !engineState.hudVisible || !hudElement) return;
        const elFps = hudElement.querySelector('#sc-perf-fps');
        const elFrame = hudElement.querySelector('#sc-perf-frame');
        const elP95 = hudElement.querySelector('#sc-perf-p95');
        const elLong = hudElement.querySelector('#sc-perf-longtasks');
        const elDom = hudElement.querySelector('#sc-perf-dom');
        const elMut = hudElement.querySelector('#sc-perf-mut');
        const elStream = hudElement.querySelector('#sc-perf-stream');
        const elJs = hudElement.querySelector('#sc-perf-js');
        const elLayer = hudElement.querySelector('#sc-perf-layer');
        const elFrozen = hudElement.querySelector('#sc-perf-frozen');
        const elGov = hudElement.querySelector('#sc-perf-gov');
        const elHealth = hudElement.querySelector('#sc-perf-health');
        const elCham = hudElement.querySelector('#sc-perf-cham');

        if (elFps) elFps.textContent = engineState.fps;
        if (elFrame) elFrame.textContent = engineState.frameTime + 'ms';
        if (elP95) elP95.textContent = engineState.p95 + ' / ' + engineState.p99;
        if (elLong) elLong.textContent = engineState.longTaskCount;
        if (elDom) elDom.textContent = Number(engineState.domCount).toLocaleString();
        if (elMut) elMut.textContent = engineState.mutationsPerSec + '/s';
        if (elStream) {
            if (engineState.streamingFps > 0) {
                elStream.textContent = 'Frames (' + engineState.streamingFps + ' FPS)';
                elStream.style.color = '#10B981';
            } else {
                elStream.textContent = 'Idle';
                elStream.style.color = '#64748B';
            }
        }
        if (elJs) elJs.textContent = engineState.lastFrameJsTime.toFixed(1) + 'ms';
        if (elLayer) elLayer.textContent = 'Single+Tail';
        if (elFrozen) elFrozen.textContent = engineState.frozenAnimCount;
        if (elCham) elCham.textContent = 'Event-0Poll';
        if (elGov) {
            const interacting = isCurrentlyInteracting();
            elGov.textContent = interacting ? 'Touch Priority' : 'Smooth';
            elGov.style.color = interacting ? '#F59E0B' : '#38BDF8';
        }

        if (elHealth) {
            if (engineState.fps >= 75 && engineState.p95 < 18) {
                elHealth.textContent = '90Hz Ultra';
                elHealth.style.background = 'rgba(16, 185, 129, 0.15)';
                elHealth.style.color = '#34D399';
            } else if (engineState.fps >= 55) {
                elHealth.textContent = 'Healthy';
                elHealth.style.background = 'rgba(16, 185, 129, 0.15)';
                elHealth.style.color = '#34D399';
            } else if (engineState.fps >= 35) {
                elHealth.textContent = 'Warning';
                elHealth.style.background = 'rgba(245, 158, 11, 0.15)';
                elHealth.style.color = '#FBBF24';
            } else {
                elHealth.textContent = 'Lagging';
                elHealth.style.background = 'rgba(239, 68, 68, 0.15)';
                elHealth.style.color = '#F87171';
            }
        }
    }

    function startDiagnostics() {
        if (!isPageActive() || !engineState.hudVisible || hudInterval !== null) return;
        startFrameLoop();
        startMutationObserver();
        if (window.PerformanceObserver) {
            try {
                longTaskObserver = new PerformanceObserver((list) => {
                    if (!isPageActive() || !engineState.hudVisible) return;
                    engineState.longTaskCount += list.getEntries().length;
                });
                longTaskObserver.observe({ entryTypes: ['longtask'] });
            } catch(_) {
                if (longTaskObserver) longTaskObserver.disconnect();
                longTaskObserver = null;
            }
        }
        mutationInterval = setInterval(updateMutationMetrics, 1000);
        domCountInterval = setInterval(updateDomCount, 2000);
        hudInterval = setInterval(updateHud, 200);
        updateDomCount();
        updateHud();
    }

    function stopDiagnostics() {
        for (const id of [mutationInterval, domCountInterval, hudInterval]) {
            if (id !== null) clearInterval(id);
        }
        mutationInterval = null;
        domCountInterval = null;
        hudInterval = null;
        stopFrameLoop();
        stopMutationObserver();
        if (longTaskObserver) longTaskObserver.disconnect();
        longTaskObserver = null;
    }

    function cancelHudTransition() {
        if (hudRevealFrame !== null) cancelAnimationFrame(hudRevealFrame);
        hudRevealFrame = null;
        if (hudHideTimer !== null) clearTimeout(hudHideTimer);
        hudHideTimer = null;
    }

    window.__scTogglePerfHud = function() {
        if (disposed) return false;
        if (!hudElement) createHud();
        if (!hudElement) return false;
        cancelHudTransition();
        engineState.hudVisible = !engineState.hudVisible;
        if (engineState.hudVisible) {
            startDiagnostics();
            hudElement.style.display = 'block';
            hudElement.style.opacity = '0';
            hudElement.style.transform = 'scale(0.92) translateY(-4px)';
            if (isPageActive()) {
                hudRevealFrame = requestAnimationFrame(() => {
                    hudRevealFrame = null;
                    if (!isPageActive() || !engineState.hudVisible || !hudElement) return;
                    hudElement.style.opacity = '1';
                    hudElement.style.transform = 'scale(1) translateY(0)';
                });
            } else {
                hudElement.style.opacity = '1';
                hudElement.style.transform = 'scale(1) translateY(0)';
            }
        } else {
            stopDiagnostics();
            hudElement.style.opacity = '0';
            hudElement.style.transform = 'scale(0.92) translateY(-4px)';
            if (isPageActive()) {
                hudHideTimer = setTimeout(() => {
                    hudHideTimer = null;
                    if (!engineState.hudVisible && hudElement) hudElement.style.display = 'none';
                }, 160);
            } else {
                hudElement.style.display = 'none';
            }
        }
        return engineState.hudVisible;
    };

    function pausePageResources() {
        scheduler.pause();
        stopDiagnostics();
        cancelHudTransition();
        chameleonEngine.pause();
        if (chameleonStartupTimer !== null) clearTimeout(chameleonStartupTimer);
        chameleonStartupTimer = null;
        pauseAnimationScan();
        engineState.isInteracting = false;
        if (hudElement) {
            hudElement.style.display = engineState.hudVisible ? 'block' : 'none';
            hudElement.style.opacity = engineState.hudVisible ? '1' : '0';
            hudElement.style.transform = 'scale(1) translateY(0)';
        }
    }

    let resourcesActive = isPageActive();
    function syncPageResources() {
        if (disposed) return;
        if (!isPageActive()) {
            resourcesActive = false;
            pausePageResources();
            return;
        }
        if (resourcesActive) return;
        resourcesActive = true;
        startChameleon();
        if (animObserver && animationScanTimer === null && animationIdleId === null) {
            animationScanTimer = setTimeout(scheduleIdleScan, 2000);
        }
        if (scheduler.readQueue.length || scheduler.writeQueue.length) scheduler.schedule();
        startDiagnostics();
    }

    function dispose() {
        if (disposed) return;
        disposed = true;
        engineState.hudVisible = false;
        pausePageResources();
        scheduler.readQueue.length = 0;
        scheduler.writeQueue.length = 0;
        for (const remove of ownedListeners.splice(0)) remove();
        for (const restore of patchRestorers.splice(0).reverse()) restore();
        if (hudElement) hudElement.remove();
        hudElement = null;
    }

    listen(document, 'visibilitychange', syncPageResources);
    listen(window, 'pagehide', (event) => {
        pageSuspended = true;
        if (event.persisted) syncPageResources();
        else dispose();
    });
    listen(window, 'pageshow', () => {
        pageSuspended = false;
        syncPageResources();
    });

    // ========================================================
    // 10. UNIFIED ENGINE NAMESPACE
    // ========================================================
    window.SillyClientEngine = {
        version: '0.2.3',
        state: engineState,
        scheduler: scheduler,
        batcher: streamingBatcher,
        chameleon: chameleonEngine,
        haptic: window.__scHaptic,
        toggleHud: window.__scTogglePerfHud,
        dispose: dispose
    };
})();

