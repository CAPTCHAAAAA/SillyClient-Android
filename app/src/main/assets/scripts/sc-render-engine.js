/**
 * SillyClient Performance Engine (P0 核心架构)
 * 1. Performance Monitor HUD (变色龙右上角连续点击 7 次呼出/收起)
 * 2. Frame Scheduler (统一合并调度器，Read/Write 分离，杜绝 Layout Thrashing)
 * 3. Streaming Batcher (30FPS 流式推流缓冲池，解耦网络接收与 DOM 渲染)
 * 4. DOM Mutation Batching (突变速率统计与批量合并)
 */
(function() {
    if (window.__scRenderEngineInstalled) return;
    window.__scRenderEngineInstalled = true;

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
    // 1. PERFORMANCE MONITOR (FPS, P95/P99, LongTask, DOM)
    // ========================================================
    const engineState = {
        fps: 60.0,
        frameTime: 16.6,
        p95: 16.6,
        p99: 16.6,
        longTaskCount: 0,
        domCount: 0,
        mutationsPerSec: 0,
        streamingFps: 0,
        lastFrameJsTime: 1.2,
        hudVisible: false
    };

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

    // 每秒采样一次 DOM 数量
    setInterval(() => {
        engineState.domCount = document.getElementsByTagName('*').length;
    }, 1000);

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
            width: 220px;
            background: rgba(18, 14, 18, 0.84);
            backdrop-filter: blur(16px) saturate(1.2);
            -webkit-backdrop-filter: blur(16px) saturate(1.2);
            border: 1px solid rgba(255, 255, 255, 0.12);
            border-radius: 12px;
            padding: 10px 12px;
            font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
            font-size: 11px;
            color: #E2E8F0;
            z-index: 999999;
            box-shadow: 0 8px 32px rgba(0, 0, 0, 0.45);
            pointer-events: auto;
            user-select: none;
            transition: opacity 160ms cubic-bezier(0.12, 0.98, 0.24, 1), transform 160ms cubic-bezier(0.12, 0.98, 0.24, 1);
            transform-origin: top right;
        `;

        hudElement.addEventListener('dblclick', (e) => {
            e.stopPropagation();
            window.__scTogglePerfHud();
        });

        hudElement.innerHTML = `
            <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:8px; border-bottom:1px solid rgba(255,255,255,0.08); padding-bottom:4px;">
                <span style="font-weight:700; color:#F59E0B; letter-spacing:0.5px;">⚡ SC ENGINE</span>
                <span id="sc-perf-health" style="font-size:10px; padding:1px 5px; border-radius:4px; background:#10B981; color:#064E3B; font-weight:700;">Healthy</span>
            </div>
            <div style="display:grid; grid-template-columns: 1fr auto; row-gap:3px; font-size:10.5px;">
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

            if (elHealth) {
                if (engineState.fps >= 55 && engineState.p95 < 24) {
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
