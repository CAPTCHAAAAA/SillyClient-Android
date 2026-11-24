package com.sillyclient.runtime

import java.util.concurrent.TimeUnit

/**
 * Android 首次/冷启动就绪轮询策略：
 * - 单次 HTTP 探活超时放宽至 2000ms（避免高负载首包 >300ms 误判）；
 * - 基础启动预算 120s，若 Node 进程仍在持续输出日志（如首次冷编译 Webpack / FUSE 加载），
 *   最长动态顺延至 180s（对齐桌面端上限）；
 * - 超时后产出不含任何用户路径或敏感信息的脱敏诊断摘要。
 */
object StartupReadinessPolicy {
    const val PROBE_TIMEOUT_MILLIS: Int = 2_000
    const val BASE_TIMEOUT_MILLIS: Long = 120_000L
    const val MAX_TIMEOUT_MILLIS: Long = 180_000L
    const val ACTIVITY_GRACE_MILLIS: Long = 30_000L

    fun isTimedOut(
        startedNanos: Long,
        nowNanos: Long,
        lastOutputNanos: Long
    ): Boolean {
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis((nowNanos - startedNanos).coerceAtLeast(0L))
        if (elapsedMillis >= MAX_TIMEOUT_MILLIS) return true
        if (elapsedMillis < BASE_TIMEOUT_MILLIS) return false
        val silentMillis = TimeUnit.NANOSECONDS.toMillis((nowNanos - lastOutputNanos).coerceAtLeast(0L))
        return silentMillis >= ACTIVITY_GRACE_MILLIS
    }

    fun formatTimeoutDiagnostic(
        elapsedSeconds: Long,
        processAlive: Boolean,
        lastFailure: TavernReadiness.Failure?,
        lastStatus: Int?,
        silentSeconds: Long
    ): String {
        val state = if (processAlive) "进程存活" else "进程已退出"
        val probePart = when {
            lastFailure == null -> "未探活"
            lastStatus != null -> "${lastFailure.name}($lastStatus)"
            else -> lastFailure.name
        }
        return "酒馆启动超时（已等待 ${elapsedSeconds.coerceAtLeast(0)}s，$state，探活：$probePart，静默 ${silentSeconds.coerceAtLeast(0)}s），请检查实例日志"
    }
}
