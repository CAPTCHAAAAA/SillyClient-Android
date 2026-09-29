/**
 * SillyClient Performance Engine (P0 + P1 深度性能架构 + 触感 & 智能回底 & 内存隔离)
 *
 * P0 核心架构:
 * 1. Performance Monitor HUD (变色龙右上角连续点击 7 次呼出/收起，或双击面板关闭)
 * 2. Frame Scheduler (统一合并调度器，Read/Write 严格分离，杜绝 Layout Thrashing)
 * 3. Streaming Batcher (30FPS 流式推流缓冲池，解耦网络突发与 DOM 渲染)
 * 4. DOM Mutation Batching (突变速率统计与聚合监控)
 *
 * P1 深度优化:
 * 5. Native DOM Virtualization & Layout Containment (视口外消息原生级跳过渲染，局部布局沙箱)
 * 6. Markdown/KaTeX/Code 沙箱隔离 (超长代码块与复杂表格独立横向滚动，手势不偏航)
 * 7. Invisible Animation Governor (视口外动画/GIF 自动挂起冻结，不占 GPU)
 * 8. Interaction Priority Governor (滑动/手势期间帧预算全量倾斜，暂停背景重采样)
 * 9. Native Haptic Bridge (原生线性马达微米级精密触感反馈)
 * 10. Minimalist Scroll-to-Bottom Button (极简半透明向下符号回底按钮)
 * 11. Image & Avatar Memory Guard (历史大图与头像 lazy/async 内存熔断保护)
 */
(function() {
    if (window.__scRenderEngineInstalled) return;
    window.__scRenderEngineInstalled = true;

    // ========================================================
    // 0. 全局引擎状态
    // ========================================================
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
        hudVisible: false
    };

    // ========================================================
    // 1. NATIVE HAPTIC BRIDGE (原生线性马达精密触感)
    // ========================================================
    window.__scHaptic = function(type) {
        try {
            if (window.SillyClientHaptic && window.SillyClientHaptic.trigger) {
                window.SillyClientHaptic.trigger(type || 'tick');
            }
        } catch(_) {}
    };

    // ========================================================
    // 2. FRAME SCHEDULER (统一单帧调度器)
    // ========================================================
    const scheduler = {
        readQueue: [],
        writeQueue: [],
        scheduled: false,

        read: function(fn) {
            this.readQueue.push(fn);
            this.schedule();
        },

        write: function(fn) {
            this.writeQueue.push(fn);
            this.schedule();
        },

        schedule: function() {
            if (this.scheduled) return;
            this.scheduled = true;
            requestAnimationFrame(() => this.flush());
        },

        flush: function() {
            const start = performance.now();
            // 先一次性执行所有读取 (避免与写混用造成的 forced reflow)
            const reads = this.readQueue.splice(0);
            for (let i = 0; i < reads.length; i++) {
                try { reads[i](); } catch(e) {}
            }

            // 再一次性批量写入 DOM
            const writes = this.writeQueue.splice(0);
            for (let i = 0; i < writes.length; i++) {
                try { writes[i](); } catch(e) {}
            }

            this.scheduled = false;
            engineState.lastFrameJsTime = performance.now() - start;
        }
    };
    window.__scFrameScheduler = scheduler;

    // ========================================================
    // 3. STREAMING BATCHER (30FPS 流式推流缓冲合并器)
    // 目标：将 token 接收频率(50~100Hz)与 DOM 渲染(30FPS)解耦
    // ========================================================
    const streamingBatcher = {
        targetEl: null,
        buffer: '',
        timer: null,
        lastFlushTime: 0,
        flushInterval: 1000 / 30, // 30 FPS 刷新率 (33.3ms)
        tokenCountThisSec: 0,
        streamingFpsCount: 0,
        isStreamingActive: false,

        push: function(el, chunk) {
            this.isStreamingActive = true;
            this.tokenCountThisSec++;
            this.targetEl = el;
            this.buffer += chunk;

            const now = performance.now();
            if (now - this.lastFlushTime >= this.flushInterval) {
                this.flush();
            } else if (!this.timer) {
                this.timer = setTimeout(() => {
                    this.timer = null;
                    this.flush();
                }, this.flushInterval - (now - this.lastFlushTime));
            }
        },

        flush: function() {
            if (!this.buffer || !this.targetEl) return;
            const content = this.buffer;
            const el = this.targetEl;
            this.buffer = '';
            this.lastFlushTime = performance.now();
            this.streamingFpsCount++;

            scheduler.write(function() {
                if (el.nodeType === 3) {
                    el.nodeValue += content;
                } else if (el.innerHTML !== undefined) {
                    el.innerHTML += content;
                }
            });
        }
    };
    window.__scStreamingBatcher = streamingBatcher;

    // ========================================================
    // 4. DOM MUTATION BATCHER (突变监控与聚合)
    // ========================================================
    let mutationCounter = 0;
    let mutationsPerSec = 0;
    try {
        const observer = new MutationObserver((mutations) => {
            mutationCounter += mutations.length;
        });
        observer.observe(document.documentElement, {
            childList: true,
            subtree: true,
            attributes: false,
            characterData: true
        });
    } catch(e) {}

    setInterval(() => {
        mutationsPerSec = mutationCounter;
        mutationCounter = 0;
        engineState.mutationsPerSec = mutationsPerSec;
        engineState.streamingFps = streamingBatcher.streamingFpsCount;
        streamingBatcher.streamingFpsCount = 0;
        if (mutationsPerSec === 0 && streamingBatcher.streamingFps === 0) {
            streamingBatcher.isStreamingActive = false;
        }
    }, 1000);

    // ========================================================
    // 5. P1: 原生 DOM 虚拟化与代码/KaTeX局部沙箱
    // ========================================================
    function setupVirtualizationAndContainment() {
        if (window.CSS && CSS.supports && CSS.supports('content-visibility', 'auto')) {
            const style = document.createElement('style');
            style.id = 'sc-p1-containment';
            style.textContent = `
                /* 视口外长消息跳过渲染树与绘制，保留全部 DOM 节点与事件绑定 */
                #chat .mes {
                    content-visibility: auto;
                    contain-intrinsic-size: auto 120px;
                    contain: layout style;
                }
                /* 最后一项正在生成的活动消息始终处于可见状态，保证流式推流与吸底平滑 */
                #chat .mes:last-child {
                    content-visibility: visible !important;
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
                /* 离屏冻结类：挂起不可见视口元素的 CSS 动画 */
                .__sc-anim-frozen, .__sc-anim-frozen * {
                    animation-play-state: paused !important;
                }
            `;
            document.head.appendChild(style);
            engineState.virtualizationActive = true;
        }
    }
    if (document.head) {
        setupVirtualizationAndContainment();
    } else {
        document.addEventListener('DOMContentLoaded', setupVirtualizationAndContainment);
    }

    // ========================================================
    // 6. P1: 视口外不可见动画挂起冻结 (Invisible Animation Freeze)
    // ========================================================
    const frozenElements = new Set();
    let animObserver = null;
    if (window.IntersectionObserver) {
        try {
            animObserver = new IntersectionObserver((entries) => {
                for (let i = 0; i < entries.length; i++) {
                    const entry = entries[i];
                    const target = entry.target;
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
                rootMargin: '80px 0px 80px 0px' // 视口上下预留 80px 缓冲区，避免边缘顿挫
            });
        } catch(e) {}
    }

    // 历史超长图文与超大头像内存熔断保护
    function protectImagesMemory() {
        const imgs = document.querySelectorAll('#chat img, .mes_avatar');
        for (let i = 0; i < imgs.length; i++) {
            const img = imgs[i];
            if (!img.getAttribute('loading')) {
                img.setAttribute('loading', 'lazy');
            }
            if (!img.getAttribute('decoding')) {
                img.setAttribute('decoding', 'async');
            }
        }
    }

    function scanAndObserveAnimations() {
        protectImagesMemory();
        if (!animObserver) return;
        const candidates = document.querySelectorAll('.mes_avatar, .mes_text img, .spinner, .rotating, .typing_indicator');
        for (let i = 0; i < candidates.length; i++) {
            animObserver.observe(candidates[i]);
        }
    }
    setInterval(() => {
        if (!engineState.isInteracting) {
            scanAndObserveAnimations();
        }
    }, 3000);

    // ========================================================
    // 7. P1: 交互优先调频器 (Interaction Priority Governor)
    // ========================================================
    let interactionResetTimer = null;
    function notifyInteraction() {
        engineState.isInteracting = true;
        if (interactionResetTimer) clearTimeout(interactionResetTimer);
        interactionResetTimer = setTimeout(() => {
            engineState.isInteracting = false;
        }, 180);
    }
    window.addEventListener('touchstart', notifyInteraction, { passive: true, capture: true });
    window.addEventListener('touchmove', notifyInteraction, { passive: true });
    window.addEventListener('scroll', notifyInteraction, { passive: true, capture: true });

    // ========================================================
    // 8. 极简半透明向下符号回底按钮 (Minimalist Scroll-to-Bottom Button)
    // ========================================================
    let scrollDownBtn = null;
    function createScrollDownButton() {
        if (scrollDownBtn || !document.body) return;
        scrollDownBtn = document.createElement('div');
        scrollDownBtn.id = 'sc-scroll-down-btn';
        scrollDownBtn.setAttribute('title', '回到底部');
        scrollDownBtn.style.cssText = `
            display: flex;
            align-items: center;
            justify-content: center;
            position: fixed;
            bottom: calc(var(--sc-nav-bottom, 18px) + 72px);
            right: 16px;
            width: 36px;
            height: 36px;
            border-radius: 50%;
            background: rgba(22, 18, 22, 0.65);
            backdrop-filter: blur(12px) saturate(1.2);
            -webkit-backdrop-filter: blur(12px) saturate(1.2);
            border: 1px solid rgba(255, 255, 255, 0.14);
            box-shadow: 0 4px 18px rgba(0, 0, 0, 0.38);
            color: rgba(255, 255, 255, 0.85);
            cursor: pointer;
            opacity: 0;
            transform: scale(0.85) translateY(6px);
            pointer-events: none;
            z-index: 99998;
            transition: opacity 180ms cubic-bezier(0.12, 0.98, 0.24, 1), transform 180ms cubic-bezier(0.12, 0.98, 0.24, 1);
            user-select: none;
        `;
        scrollDownBtn.innerHTML = `
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                <polyline points="6 9 12 15 18 9"></polyline>
            </svg>
        `;

        scrollDownBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            window.__scHaptic('tick');
            const chat = document.getElementById('chat');
            if (chat && chat.scrollHeight > chat.clientHeight) {
                chat.scrollTo({ top: chat.scrollHeight, behavior: 'smooth' });
            } else {
                window.scrollTo({ top: document.body.scrollHeight, behavior: 'smooth' });
            }
        });

        document.body.appendChild(scrollDownBtn);
    }

    function checkScrollDownState() {
        if (!scrollDownBtn) createScrollDownButton();
        if (!scrollDownBtn) return;
        const chat = document.getElementById('chat');
        const scroller = (chat && chat.scrollHeight > chat.clientHeight) ? chat : document.documentElement;
        const distFromBottom = scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight;
        if (distFromBottom > 420) {
            scrollDownBtn.style.opacity = '1';
            scrollDownBtn.style.transform = 'scale(1) translateY(0)';
            scrollDownBtn.style.pointerEvents = 'auto';
        } else {
            scrollDownBtn.style.opacity = '0';
            scrollDownBtn.style.transform = 'scale(0.85) translateY(6px)';
            scrollDownBtn.style.pointerEvents = 'none';
        }
    }
    window.addEventListener('scroll', checkScrollDownState, { passive: true, capture: true });
    if (document.body) {
        createScrollDownButton();
    } else {
        document.addEventListener('DOMContentLoaded', createScrollDownButton);
    }

    // ========================================================
    // 9. PERFORMANCE MONITOR (FPS, P95/P99, LongTask, DOM)
    // ========================================================
    // LongTask 监测 (Chromium PerformanceObserver)
    if (window.PerformanceObserver) {
        try {
            const longTaskObserver = new PerformanceObserver((list) => {
                const entries = list.getEntries();
                engineState.longTaskCount += entries.length;
            });
            longTaskObserver.observe({ entryTypes: ['longtask'] });
        } catch(e) {}
    }

    // 实时 FPS 与 P95/P99 计算器 (滑动窗口 60 帧)
    const frameTimes = [];
    let lastTimestamp = performance.now();

    function frameLoop(now) {
        const delta = now - lastTimestamp;
        lastTimestamp = now;

        if (delta > 0 && delta < 500) {
            frameTimes.push(delta);
            if (frameTimes.length > 60) frameTimes.shift();

            const avg = frameTimes.reduce((a, b) => a + b, 0) / frameTimes.length;
            engineState.fps = Math.min(120, +(1000 / avg).toFixed(1));
            engineState.frameTime = +avg.toFixed(1);

            const sorted = [...frameTimes].sort((a, b) => a - b);
            const idx95 = Math.floor(sorted.length * 0.95);
            const idx99 = Math.floor(sorted.length * 0.99);
            engineState.p95 = +(sorted[idx95] || avg).toFixed(1);
            engineState.p99 = +(sorted[idx99] || avg).toFixed(1);
        }

        requestAnimationFrame(frameLoop);
    }
    requestAnimationFrame(frameLoop);

    // 每秒采样一次 DOM 数量 (滑动交互期间跳过，避免阻塞主线程)
    setInterval(() => {
        if (!engineState.isInteracting) {
            engineState.domCount = document.getElementsByTagName('*').length;
        }
    }, 1200);

    // ========================================================
    // HUD UI 渲染组件 (极客暗金毛玻璃微浮窗)
    // ========================================================
    let hudElement = null;

    function createHud() {
        if (hudElement) return;
        hudElement = document.createElement('div');
        hudElement.id = 'sc-perf-hud';
        hudElement.style.cssText = `
            display: none;
            position: fixed;
            top: calc(var(--topBarBlockSize, 42px) + 8px);
            right: 12px;
            width: 232px;
            background: rgba(18, 14, 18, 0.86);
            backdrop-filter: blur(16px) saturate(1.25);
            -webkit-backdrop-filter: blur(16px) saturate(1.25);
            border: 1px solid rgba(255, 255, 255, 0.12);
            border-radius: 12px;
            padding: 10px 12px;
            font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
            font-size: 11px;
            color: #E2E8F0;
            z-index: 999999;
            box-shadow: 0 8px 32px rgba(0, 0, 0, 0.50);
            pointer-events: auto;
            user-select: none;
            transition: opacity 160ms cubic-bezier(0.12, 0.98, 0.24, 1), transform 160ms cubic-bezier(0.12, 0.98, 0.24, 1);
            transform-origin: top right;
        `;

        hudElement.addEventListener('dblclick', (e) => {
            e.stopPropagation();
            window.__scHaptic('tick');
            window.__scTogglePerfHud();
        });

        hudElement.innerHTML = `
            <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:8px; border-bottom:1px solid rgba(255,255,255,0.08); padding-bottom:4px;">
                <span style="font-weight:700; color:#F59E0B; letter-spacing:0.5px;">⚡ SC ENGINE</span>
                <span id="sc-perf-health" style="font-size:10px; padding:1px 5px; border-radius:4px; background:#10B981; color:#064E3B; font-weight:700;">Healthy</span>
            </div>
            <div style="display:grid; grid-template-columns: 1fr auto; row-gap:3.5px; font-size:10.5px;">
                <span style="color:#94A3B8;">FPS</span>
                <span id="sc-perf-fps" style="font-weight:600; color:#38BDF8;">--</span>

                <span style="color:#94A3B8;">Frame Time</span>
                <span id="sc-perf-frame">-- ms</span>

                <span style="color:#94A3B8;">P95 / P99</span>
                <span id="sc-perf-p95">-- / --</span>

                <span style="color:#94A3B8;">Long Tasks</span>
                <span id="sc-perf-longtasks" style="color:#F43F5E;">0</span>

                <span style="color:#94A3B8;">DOM Count</span>
                <span id="sc-perf-dom">--</span>

                <span style="color:#94A3B8;">Mutations</span>
                <span id="sc-perf-mut">--/s</span>

                <span style="color:#94A3B8;">Streaming</span>
                <span id="sc-perf-stream">Idle</span>

                <span style="color:#94A3B8;">JS Frame</span>
                <span id="sc-perf-js">-- ms</span>

                <span style="color:#94A3B8;">Virtualization</span>
                <span id="sc-perf-virt" style="color:#10B981; font-weight:600;">Active</span>

                <span style="color:#94A3B8;">Frozen Anims</span>
                <span id="sc-perf-frozen" style="color:#A78BFA;">0</span>

                <span style="color:#94A3B8;">Governor</span>
                <span id="sc-perf-gov" style="color:#38BDF8;">Smooth</span>
            </div>
            <div style="margin-top:8px; text-align:center; font-size:9px; color:#64748B;">
                连续点击右上角 7 次或双击面板关闭
            </div>
        `;

        document.body.appendChild(hudElement);

        setInterval(() => {
            if (!engineState.hudVisible || !hudElement) return;
            const elFps = hudElement.querySelector('#sc-perf-fps');
            const elFrame = hudElement.querySelector('#sc-perf-frame');
            const elP95 = hudElement.querySelector('#sc-perf-p95');
            const elLong = hudElement.querySelector('#sc-perf-longtasks');
            const elDom = hudElement.querySelector('#sc-perf-dom');
            const elMut = hudElement.querySelector('#sc-perf-mut');
            const elStream = hudElement.querySelector('#sc-perf-stream');
            const elJs = hudElement.querySelector('#sc-perf-js');
            const elVirt = hudElement.querySelector('#sc-perf-virt');
            const elFrozen = hudElement.querySelector('#sc-perf-frozen');
            const elGov = hudElement.querySelector('#sc-perf-gov');
            const elHealth = hudElement.querySelector('#sc-perf-health');

            if (elFps) elFps.textContent = engineState.fps;
            if (elFrame) elFrame.textContent = engineState.frameTime + 'ms';
            if (elP95) elP95.textContent = engineState.p95 + ' / ' + engineState.p99;
            if (elLong) elLong.textContent = engineState.longTaskCount;
            if (elDom) elDom.textContent = Number(engineState.domCount).toLocaleString();
            if (elMut) elMut.textContent = engineState.mutationsPerSec + '/s';
            if (elStream) {
                elStream.textContent = engineState.streamingFps > 0 ? (engineState.streamingFps + ' FPS') : 'Idle';
                elStream.style.color = engineState.streamingFps > 0 ? '#10B981' : '#94A3B8';
            }
            if (elJs) elJs.textContent = engineState.lastFrameJsTime.toFixed(1) + 'ms';
            if (elVirt) elVirt.textContent = engineState.virtualizationActive ? 'Active' : 'Bypassed';
            if (elFrozen) elFrozen.textContent = engineState.frozenAnimCount;
            if (elGov) {
                elGov.textContent = engineState.isInteracting ? 'Touch Priority' : 'Smooth';
                elGov.style.color = engineState.isInteracting ? '#F59E0B' : '#38BDF8';
            }

            if (elHealth) {
                if (engineState.fps >= 75 && engineState.p95 < 18) {
                    elHealth.textContent = 'Ultra 90Hz';
                    elHealth.style.background = '#10B981';
                    elHealth.style.color = '#064E3B';
                } else if (engineState.fps >= 55) {
                    elHealth.textContent = 'Healthy';
                    elHealth.style.background = '#10B981';
                    elHealth.style.color = '#064E3B';
                } else if (engineState.fps >= 35) {
                    elHealth.textContent = 'Warning';
                    elHealth.style.background = '#F59E0B';
                    elHealth.style.color = '#78350F';
                } else {
                    elHealth.textContent = 'Lagging';
                    elHealth.style.background = '#EF4444';
                    elHealth.style.color = '#7F1D1D';
                }
            }
        }, 200);
    }

    window.__scTogglePerfHud = function() {
        if (!hudElement) createHud();
        engineState.hudVisible = !engineState.hudVisible;
        if (engineState.hudVisible) {
            hudElement.style.display = 'block';
            hudElement.style.opacity = '0';
            hudElement.style.transform = 'scale(0.92) translateY(-4px)';
            requestAnimationFrame(() => {
                hudElement.style.opacity = '1';
                hudElement.style.transform = 'scale(1) translateY(0)';
            });
        } else {
            hudElement.style.opacity = '0';
            hudElement.style.transform = 'scale(0.92) translateY(-4px)';
            setTimeout(() => {
                if (!engineState.hudVisible && hudElement) {
                    hudElement.style.display = 'none';
                }
            }, 160);
        }
        return engineState.hudVisible;
    };
})();
