/**
 * SillyClient Performance Engine (P0 + P1 深度性能架构 + 全站微震)
 *
 * 核心功能:
 * 1. Performance Monitor HUD (美化符合 SC 整体前端规范，支持双击/7连击收起，内置触感开关)
 * 2. 全站交互线性马达微震 (发送、抽屉、Swipe、角色卡、按钮全覆盖，支持开关记忆)
 * 3. Frame Scheduler (统一合并调度器，Read/Write 严格分离)
 * 4. Streaming Batcher (30FPS 流式推流缓冲池)
 * 5. Native DOM Virtualization & Layout Containment (视口外原生虚拟化，代码块/KaTeX横向沙箱)
 * 6. Invisible Animation Freeze (视口外动画/GIF 自动挂起冻结)
 * 7. Interaction Priority Governor (滑动/手势期间帧预算全量倾斜)
 * 8. Image & Avatar Memory Guard (历史大图与头像 lazy/async 内存熔断保护)
 */
(function() {
    if (window.__scRenderEngineInstalled) return;
    window.__scRenderEngineInstalled = true;

    // ========================================================
    // 0. 全局引擎状态 & 触感持久化配置
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
        hapticEnabled: localStorage.getItem('__sc_haptic_enabled') === '1',
        hudVisible: false
    };

    // ========================================================
    // 1. NATIVE HAPTIC BRIDGE (原生线性马达精密触感引擎)
    // ========================================================
    window.__scHaptic = function(type) {
        if (!engineState.hapticEnabled) return;
        try {
            if (window.SillyClientHaptic && window.SillyClientHaptic.trigger) {
                window.SillyClientHaptic.trigger(type || 'tick');
            }
        } catch(_) {}
    };

    // 酒馆全站高频交互触觉委托捕获
    document.addEventListener('pointerdown', (e) => {
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
            const reads = this.readQueue.splice(0);
            for (let i = 0; i < reads.length; i++) {
                try { reads[i](); } catch(e) {}
            }

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
    // ========================================================
    const streamingBatcher = {
        targetEl: null,
        buffer: '',
        timer: null,
        lastFlushTime: 0,
        flushInterval: 1000 / 30,
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

            const chat = document.getElementById('chat');
            let shouldStickBottom = false;

            // 1. 严格在单帧调度器 Read 阶段判定用户是否处于底部附近 (< 120px)
            if (chat) {
                scheduler.read(function() {
                    const scrollDist = chat.scrollHeight - chat.scrollTop - chat.clientHeight;
                    shouldStickBottom = scrollDist < 120;
                });
            }

            // 2. 严格在单帧调度器 Write 阶段原子化写入内容并驱动平滑吸底（杜绝回流颠簸）
            scheduler.write(function() {
                if (el.nodeType === 3) {
                    el.nodeValue += content;
                } else if (el.innerHTML !== undefined) {
                    el.innerHTML += content;
                }
                if (shouldStickBottom && chat) {
                    chat.scrollTop = chat.scrollHeight;
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
                /* 1. 消除外层 html/body 双重滚动分层争抢，让单一物理滚动图层收敛在 #chat */
                html, body {
                    overflow: hidden !important;
                    height: 100% !important;
                    overscroll-behavior: none !important;
                }
                #chat {
                    overscroll-behavior-y: contain !important;
                    -webkit-overflow-scrolling: touch !important;
                    transform: translateZ(0); /* 提升为主合成层，隔离顶栏重绘 */
                    touch-action: pan-y pinch-zoom; /* 纵向滑动直通合成器，绕过主线程 JS 计算 */
                }
                /* 2. 现代输入框原生尺寸通道：消除 JS 频繁读取 scrollHeight 造成的强制回流 */
                #send_textarea {
                    field-sizing: content !important;
                    max-height: 160px !important;
                    contain: layout style !important;
                }
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
                /* 头像与静态资源局部绘制隔离：杜绝 GIF 头像扩散重绘 */
                .mes_avatar {
                    contain: paint layout !important;
                    transform: translateZ(0);
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
                rootMargin: '80px 0px 80px 0px'
            });
        } catch(e) {}
    }

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

    // 利用 requestIdleCallback 在浏览器主线程空闲阶段（>3ms）执行后台扫描，杜绝关键帧竞争
    function scheduleIdleScan() {
        const run = (deadline) => {
            if (!engineState.isInteracting && (!deadline || deadline.timeRemaining() > 3)) {
                scanAndObserveAnimations();
            }
            setTimeout(scheduleIdleScan, 4000);
        };
        if (window.requestIdleCallback) {
            window.requestIdleCallback(run, { timeout: 6000 });
        } else {
            setTimeout(run, 4000);
        }
    }
    setTimeout(scheduleIdleScan, 2000);

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

    // 清理可能遗留的旧回底按钮残留
    const staleScrollBtn = document.getElementById('sc-scroll-down-btn');
    if (staleScrollBtn) staleScrollBtn.remove();

    // ========================================================
    // 8. PERFORMANCE MONITOR & HUD (美化对齐 SC 整体前端规范)
    // ========================================================
    if (window.PerformanceObserver) {
        try {
            const longTaskObserver = new PerformanceObserver((list) => {
                const entries = list.getEntries();
                engineState.longTaskCount += entries.length;
            });
            longTaskObserver.observe({ entryTypes: ['longtask'] });
        } catch(e) {}
    }

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

    // DOM 数量仅在 HUD 可见时，或低频空闲采样，日常运行零开销
    setInterval(() => {
        if (!engineState.isInteracting && engineState.hudVisible) {
            engineState.domCount = document.getElementsByTagName('*').length;
        }
    }, 2000);

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
            width: 236px;
            background: rgba(15, 17, 23, 0.88);
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

        hudElement.addEventListener('dblclick', (e) => {
            e.stopPropagation();
            window.__scHaptic('tick');
            window.__scTogglePerfHud();
        });

        hudElement.innerHTML = `
            <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:10px; border-bottom:1px solid rgba(255,255,255,0.08); padding-bottom:6px;">
                <div style="display:flex; align-items:center; gap:6px;">
                    <span style="display:inline-block; width:6px; height:6px; border-radius:50%; background:#10B981; box-shadow:0 0 8px #10B981;"></span>
                    <span style="font-weight:700; color:#EAB308; letter-spacing:0.6px; font-size:11px;">SC PERFORMANCE</span>
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

                <span style="color:#64748B;">Virtualization</span>
                <span id="sc-perf-virt" style="color:#10B981; font-weight:600;">Active</span>

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
            toggleBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                engineState.hapticEnabled = !engineState.hapticEnabled;
                localStorage.setItem('__sc_haptic_enabled', engineState.hapticEnabled ? '1' : '0');
                toggleBtn.style.background = engineState.hapticEnabled ? '#10B981' : '#334155';
                knob.style.left = engineState.hapticEnabled ? '18px' : '2px';
                if (engineState.hapticEnabled) {
                    window.__scHaptic('click');
                }
            });
        }

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
                elStream.style.color = engineState.streamingFps > 0 ? '#10B981' : '#64748B';
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
        }, 200);
    }

    window.__scTogglePerfHud = function() {
        if (!hudElement) createHud();
        engineState.hudVisible = !engineState.hudVisible;
        if (engineState.hudVisible) {
            try { engineState.domCount = document.getElementsByTagName('*').length; } catch(_) {}
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
