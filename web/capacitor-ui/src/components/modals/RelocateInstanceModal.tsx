import React, { useState, useEffect, useRef, useLayoutEffect } from "react";
import {
  X,
  AlertCircle,
  Loader2,
  Info,
} from "lucide-react";
import { cn, formatDisplayVersion } from "../../lib/utils";
import { LAYERS } from "../../constants/layers";
import { LayerBackdrop } from "../common/LayerBackdrop";
import { TarvenEnv } from "../../capacitor-plugin";
import type { InstanceRelocationResult } from "../../capacitor-plugin";
import { exactInstallTarget, installationSelection } from "../../lib/install-location";
import { requireRelocationResult } from "../../lib/instance-location-state";
import type { TavernInstance } from "../../types";

export interface RelocateInstanceModalProps {
  instance: TavernInstance | null;
  isOpen: boolean;
  isClosing?: boolean;
  onClose: () => void;
  isLight: boolean;
  glassBg: string;
  isWindows?: boolean;
  onBusyChange?: (busy: boolean) => void;
  onRelocated?: (previousId: string, result: InstanceRelocationResult) => void;
}

/**
 * 单实例存储路径无损重定位弹窗 (RelocateInstanceModal)
 * 1. 采用阻断级 Z-Index (LAYERS.DIALOG_SURFACE 与 LAYERS.DIALOG_BACKDROP)；
 * 2. 具有物理弹性入场与离场动画 (.animate-modal-dialog)；
 * 3. 默认目录与自定义目录通过 motion-panel-stack / motion-panel-face 实现平滑高度跟随与交叉溶变；
 * 4. 彻底去除冗余方框嵌套与对勾图标，控件字体与交互行为 100% 对齐向导；
 * 5. 底部保留关于受管实例直接无损迁移的设计哲学说明。
 */
export const RelocateInstanceModal: React.FC<RelocateInstanceModalProps> = ({
  instance,
  isOpen,
  isClosing = false,
  onClose,
  isLight,
  glassBg,
  isWindows = false,
  onBusyChange,
  onRelocated,
}) => {
  const [targetMode, setTargetMode] = useState<"default" | "custom">("default");
  const [customPath, setCustomPath] = useState("");
  const [currentRealPath, setCurrentRealPath] = useState<string>("");
  const [migrating, setMigrating] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [successInfo, setSuccessInfo] = useState<InstanceRelocationResult | null>(null);
  const [progressInfo, setProgressInfo] = useState<{ percent: number; stage: string } | null>(null);
  const sessionRef = useRef(0);
  const busyRef = useRef(false);

  const panelContainerRef = useRef<HTMLDivElement>(null);
  const defaultFaceRef = useRef<HTMLDivElement>(null);
  const customFaceRef = useRef<HTMLDivElement>(null);
  const [panelHeight, setPanelHeight] = useState<number | null>(null);

  // 平滑自适应高度测量与交叉溶变过渡
  useLayoutEffect(() => {
    if (!isOpen) return;
    const targetEl = targetMode === "default" ? defaultFaceRef.current : customFaceRef.current;
    if (!targetEl) return;

    const measureHeight = (entry?: ResizeObserverEntry) => {
      const h = entry?.borderBoxSize?.[0]?.blockSize ?? targetEl.offsetHeight;
      if (h > 0) {
        setPanelHeight(Math.ceil(h));
      }
    };

    measureHeight();

    if (typeof ResizeObserver !== "undefined") {
      const ro = new ResizeObserver(([entry]) => {
        measureHeight(entry);
      });
      ro.observe(targetEl);
      return () => ro.disconnect();
    }
  }, [targetMode, isOpen, customPath]);

  const handleSwitchMode = (mode: "default" | "custom") => {
    if (mode === targetMode) return;
    if (panelContainerRef.current) {
      setPanelHeight(panelContainerRef.current.offsetHeight);
    }
    setTargetMode(mode);
  };

  useEffect(() => {
    const session = ++sessionRef.current;
    setCurrentRealPath(instance?.installPath || "");
    setErrorMsg(null);
    setSuccessInfo(null);
    setProgressInfo(null);
    setTargetMode("default");
    setCustomPath("");
    setPanelHeight(null);
    if (!isOpen || !instance) {
      setMigrating(false);
      return;
    }

    // 获取当前实例的真实物理路径
    const safeId = instance.installDir || instance.id;
    TarvenEnv.getInstanceInfo({
      instanceId: safeId,
      installPath: instance.installPath,
      port: instance.port ?? 8000,
    })
      .then((info) => {
        if (sessionRef.current !== session) return;
        if (info.path) setCurrentRealPath(info.path);
        else setCurrentRealPath(instance.installPath || "—");
      })
      .catch(() => {
        if (sessionRef.current !== session) return;
        setCurrentRealPath(instance.installPath || "—");
      });
    return () => { sessionRef.current++; };
  }, [isOpen, instance?.id]);

  if (!isOpen && !isClosing) return null;
  if (!instance) return null;

  const isRunning = instance.status === "running";

  const handlePickDirectory = async () => {
    if (busyRef.current || isClosing) return;
    const session = sessionRef.current;
    try {
      const res = await TarvenEnv.pickDirectory({
        purpose: "installation",
      });
      if (session !== sessionRef.current || busyRef.current) return;
      const selected = installationSelection(res);
      setCustomPath(exactInstallTarget(selected.path, selected.mode, instance.subtitle || instance.name) || "");
    } catch (error) {
      if (session === sessionRef.current && error instanceof Error && !/cancel/i.test(error.message)) setErrorMsg(error.message);
    }
  };

  const handleExecuteRelocate = async () => {
    if (isRunning || busyRef.current || isClosing) return;
    const session = ++sessionRef.current;
    busyRef.current = true;
    onBusyChange?.(true);
    setErrorMsg(null);
    setMigrating(true);

    let progressHandle: { remove: () => Promise<void> } | null = null;
    try {
      const safeId = instance.installDir || instance.id;
      const target = targetMode === "custom" ? exactInstallTarget(customPath, "exact", instance.id) : undefined;

      if (targetMode === "custom" && !target) {
        throw new Error("请先选择或输入自定义目标目录");
      }

      progressHandle = await TarvenEnv.addListener("progress", d => {
        if (d.source === "command" || sessionRef.current !== session) return;
        if (d.instanceId !== safeId || d.percent === undefined || !d.stage) return;
        setProgressInfo({ percent: d.percent, stage: d.stage });
      });

      const res = await TarvenEnv.relocateInstance({
        instanceId: safeId,
        targetPath: target,
        installPath: instance.installPath,
      });
      requireRelocationResult(res);
      onRelocated?.(instance.id, res);
      if (session === sessionRef.current) {
        setSuccessInfo(res);
        setCurrentRealPath(res.newPath);
      }
    } catch (err: any) {
      if (session === sessionRef.current) setErrorMsg(err?.message || "迁移失败，请检查路径权限后重试");
    } finally {
      void progressHandle?.remove().catch(() => {});
      busyRef.current = false;
      onBusyChange?.(false);
      if (session === sessionRef.current) setMigrating(false);
    }
  };

  return (
    <>
      <LayerBackdrop
        isOpen={isOpen}
        isClosing={isClosing}
        onClick={migrating ? undefined : onClose}
        zIndex={LAYERS.DIALOG_BACKDROP}
        blur={true}
        className={cn(
          "transition-all duration-300",
          isLight ? "bg-black/25 backdrop-blur-[12px]" : "bg-black/55 backdrop-blur-[12px]"
        )}
      />

      <div
        className={cn(
          "ios-task-surface fixed rounded-2xl flex flex-col overflow-hidden backdrop-blur-[40px] saturate-180",
          glassBg,
          isLight && "is-light",
          isClosing ? "animate-modal-dialog-exit" : "animate-modal-dialog"
        )}
        style={{
          zIndex: LAYERS.DIALOG_SURFACE,
          top: "50%",
          left: "50%",
          transform: "translate(-50%, -50%)",
          width: "min(460px, calc(100vw - 2rem))",
          maxHeight: "min(85vh, calc(100vh - 4rem))",
        }}
      >
        {/* 顶部标题栏 */}
        <div
          className={cn(
            "flex items-center justify-between px-5 h-12 flex-shrink-0 border-b",
            isLight ? "border-black/[0.06]" : "border-white/[0.06]"
          )}
        >
          <span className={cn("text-sm font-semibold", isLight ? "text-[#1a1625]" : "text-white")}>
            迁移实例存储目录
          </span>
          {!migrating && (
            <button
              onClick={onClose}
              className={cn(
                "p-1.5 rounded-lg transition-colors",
                isLight
                  ? "hover:bg-black/5 text-[#1a1625]/30 hover:text-[#1a1625]/60"
                  : "hover:bg-white/5 text-white/30 hover:text-white/60"
              )}
              aria-label="关闭"
            >
              <X className="w-4 h-4" />
            </button>
          )}
        </div>

        {/* 正文区域 */}
        <div className="flex-1 overflow-y-auto p-5 space-y-4 scrollbar-hidden text-xs">
          {/* 当前实例信息 (扁平无多重框) */}
          <div className="space-y-1">
            <div className="flex items-center justify-between gap-3">
              <span className={cn("font-medium text-xs truncate", isLight ? "text-[#1a1625]" : "text-white")}>
                {instance.subtitle || instance.name}
              </span>
              <span
                className={cn(
                  "px-2 py-0.5 rounded-md text-[10px] font-semibold tracking-wide border flex-shrink-0",
                  isLight
                    ? "bg-black/[0.06] text-[#1a1625]/55 border-black/[0.08]"
                    : "bg-white/[0.08] text-white/50 border-white/[0.08]"
                )}
              >
                {formatDisplayVersion(instance.version)}
              </span>
            </div>
            <div className="text-[11px] font-mono break-all opacity-45 leading-relaxed">
              当前路径: {currentRealPath || "读取中..."}
            </div>
          </div>

          {/* 运行状态警告 */}
          {isRunning && (
            <div className="p-3 rounded-xl bg-amber-500/10 border border-amber-500/20 text-amber-500 text-xs flex items-center gap-2">
              <AlertCircle className="w-4 h-4 flex-shrink-0" />
              <span>当前实例正在运行，请先在控制台停止后再执行迁移。</span>
            </div>
          )}

          {/* 成功状态 */}
          {successInfo ? (
            <div className="space-y-2 py-1">
              <div className={cn("text-xs font-medium", isLight ? "text-[#1a1625]" : "text-white")}>
                实例数据无损迁移完成
              </div>
              <div
                className={cn(
                  "text-[11px] font-mono break-all p-3 rounded-xl border leading-relaxed",
                  isLight ? "bg-black/[0.02] border-black/[0.06]" : "bg-white/[0.03] border-white/[0.06]"
                )}
              >
                {successInfo.newPath}
              </div>
              <p className="text-[11px] opacity-50 leading-relaxed break-all">
                {successInfo.retainedSourcePath
                  ? `新路径已启用，原目录正在后台自动清理：${successInfo.retainedSourcePath}`
                  : "实例位置已同步，可从新路径继续启动。"}
              </p>
            </div>
          ) : migrating ? (
            <div className="space-y-3 py-1">
              <div className="flex items-center justify-between gap-3">
                <span className={cn("text-xs font-medium truncate", isLight ? "text-[#1a1625]" : "text-white")}>
                  {progressInfo?.stage || "正在准备迁移"}
                </span>
                <span
                  className={cn(
                    "text-[11px] font-mono tabular-nums flex-shrink-0",
                    isLight ? "text-[#1a1625]/50" : "text-white/50"
                  )}
                >
                  {progressInfo ? `${progressInfo.percent}%` : "···"}
                </span>
              </div>
              <div
                className={cn(
                  "h-1.5 rounded-full overflow-hidden",
                  isLight ? "bg-black/[0.06]" : "bg-white/[0.10]"
                )}
              >
                <div
                  className={cn(
                    "h-full rounded-full transition-[width] duration-500 ease-out",
                    isLight ? "bg-[#1a1625]/60" : "bg-white/60"
                  )}
                  style={{ width: progressInfo ? `${progressInfo.percent}%` : "0%" }}
                />
              </div>
              <p className={cn("text-[11px] leading-relaxed", isLight ? "text-[#1a1625]/45" : "text-white/45")}>
                正在校验复制内容，原目录在校验完成前保持完整；请保持应用在前台直至迁移结束。
              </p>
            </div>
          ) : (
            <>
              {/* 目标位置分段切换器 (完全对齐向导模式切换器) */}
              <div className="space-y-1.5">
                <label className={cn("text-xs font-medium block", isLight ? "text-[#1a1625]/70" : "text-white/70")}>
                  目标位置
                </label>
                <div className="flex gap-2">
                  <button
                    type="button"
                    disabled={migrating}
                    onClick={() => handleSwitchMode("default")}
                    aria-pressed={targetMode === "default"}
                    className={cn(
                      "ios-choice-control motion-control flex-1 h-9 rounded-xl text-xs font-medium border transition-colors duration-500 ease-[cubic-bezier(0.22,1,0.36,1)]",
                      targetMode === "default"
                        ? isLight
                          ? "bg-[#1a1625]/8 border-[#1a1625]/15 text-[#1a1625]"
                          : "bg-white/10 border-white/15 text-white"
                        : isLight
                        ? "bg-transparent border-black/[0.06] text-[#1a1625]/35 hover:border-black/12 hover:text-[#1a1625]/55"
                        : "bg-transparent border-white/[0.06] text-white/35 hover:border-white/12 hover:text-white/55"
                    )}
                  >
                    默认目录 (instances/)
                  </button>

                  <button
                    type="button"
                    disabled={migrating}
                    onClick={() => handleSwitchMode("custom")}
                    aria-pressed={targetMode === "custom"}
                    className={cn(
                      "ios-choice-control motion-control flex-1 h-9 rounded-xl text-xs font-medium border transition-colors duration-500 ease-[cubic-bezier(0.22,1,0.36,1)]",
                      targetMode === "custom"
                        ? isLight
                          ? "bg-[#1a1625]/8 border-[#1a1625]/15 text-[#1a1625]"
                          : "bg-white/10 border-white/15 text-white"
                        : isLight
                        ? "bg-transparent border-black/[0.06] text-[#1a1625]/35 hover:border-black/12 hover:text-[#1a1625]/55"
                        : "bg-transparent border-white/[0.06] text-white/35 hover:border-white/12 hover:text-white/55"
                    )}
                  >
                    自定义目录
                  </button>
                </div>
              </div>

              {/* 模式配置切换容器 (平滑高度自适应 + 500ms 交叉溶变过渡) */}
              <div
                ref={panelContainerRef}
                className="motion-panel-stack"
                style={{ height: panelHeight ? `${panelHeight}px` : undefined }}
              >
                {/* 默认目录说明 */}
                <div
                  ref={defaultFaceRef}
                  className={cn(
                    "motion-panel-face w-full space-y-1.5",
                    targetMode === "default"
                      ? "is-active relative pointer-events-auto"
                      : "absolute inset-x-0 top-0 pointer-events-none select-none"
                  )}
                  aria-hidden={targetMode !== "default"}
                  inert={targetMode !== "default"}
                >
                  <p className={cn("text-xs leading-relaxed opacity-60", isLight ? "text-[#1a1625]" : "text-white")}>
                    {isWindows ? "将实例迁至客户端默认的 " : "将实例迁至应用管理的外部 "}<code className="px-1 py-0.5 rounded bg-black/5 dark:bg-white/10 font-mono text-[11px]">instances/</code> 目录，保留聊天记录、角色、扩展与配置。
                  </p>
                </div>

                {/* 自定义目录表单 */}
                <div
                  ref={customFaceRef}
                  className={cn(
                    "motion-panel-face w-full space-y-1.5",
                    targetMode === "custom"
                      ? "is-active relative pointer-events-auto"
                      : "absolute inset-x-0 top-0 pointer-events-none select-none"
                  )}
                  aria-hidden={targetMode !== "custom"}
                  inert={targetMode !== "custom"}
                >
                  <div className="flex items-center gap-2 w-full">
                    <input
                      type="text"
                      value={customPath}
                      disabled={migrating}
                      onChange={(e) => setCustomPath(e.target.value)}
                      placeholder="选择或输入完整目标目录绝对路径"
                      className={cn(
                        "ios-field-control motion-control flex-1 min-w-0 h-9 px-3 rounded-xl border text-xs transition-colors",
                        isLight
                          ? "bg-black/[0.04] border-black/[0.08] text-[#1a1625] placeholder:text-black/30 focus:border-black/20"
                          : "bg-white/[0.04] border-white/[0.08] text-white placeholder:text-white/30 focus:border-white/20"
                      )}
                    />
                    <button
                      type="button"
                      onClick={handlePickDirectory}
                      disabled={migrating}
                      className={cn(
                        "motion-control h-9 px-3 rounded-xl text-[11px] font-medium border flex-shrink-0 transition-colors",
                        isLight
                          ? "border-black/[0.08] text-[#1a1625]/50 hover:bg-black/[0.04]"
                          : "border-white/[0.08] text-white/50 hover:bg-white/[0.04]"
                      )}
                    >
                      浏览
                    </button>
                  </div>
                  <p className={cn("text-[11px] opacity-45 leading-relaxed", isLight ? "text-[#1a1625]" : "text-white")}>
                    目标必须是可访问的新目录。跨存储迁移时校验副本；需要保留的原目录会在完成后明确列出。
                  </p>
                </div>
              </div>

              {/* 异常提示 */}
              {errorMsg && (
                <div className="p-3 rounded-xl bg-red-500/10 border border-red-500/20 text-red-400 text-xs flex items-start gap-2">
                  <AlertCircle className="w-4 h-4 flex-shrink-0 mt-0.5" />
                  <span>{errorMsg}</span>
                </div>
              )}

              {/* 底部小字解释说明（产品设计哲学说明，仅保留 information 图标） */}
              <div className="flex items-start gap-2 pt-1 text-[11px] leading-relaxed opacity-55">
                <Info className="w-3.5 h-3.5 flex-shrink-0 mt-0.5" />
                <p>
                  “导入旧酒馆”用于接入外部数据；此处只迁移当前受管实例并同步其位置。同一存储采用目录移动，跨存储采用校验复制，不覆盖目标中的已有文件。
                </p>
              </div>
            </>
          )}
        </div>

        {/* 底部按钮栏 */}
        <div
          className={cn(
            "px-5 py-4 flex-shrink-0 border-t flex items-center justify-end gap-2.5",
            isLight ? "border-black/[0.06] bg-black/[0.01]" : "border-white/[0.06] bg-white/[0.01]"
          )}
        >
          {successInfo ? (
            <button
              onClick={onClose}
              className={cn(
                "motion-control px-5 h-8 rounded-full text-xs font-medium transition-all border",
                isLight
                  ? "bg-black/[0.08] border-black/[0.10] text-[#1a1625] hover:bg-black/[0.14]"
                  : "bg-white/20 border-white/15 text-white hover:bg-white/30"
              )}
            >
              完成
            </button>
          ) : (
            <>
              <button
                type="button"
                disabled={migrating}
                onClick={onClose}
                className={cn(
                  "motion-control px-4 h-8 rounded-full text-xs font-medium transition-all border disabled:opacity-40",
                  isLight
                    ? "bg-transparent border-black/[0.08] text-[#1a1625]/60 hover:bg-black/[0.04]"
                    : "bg-transparent border-white/[0.08] text-white/60 hover:bg-white/[0.04]"
                )}
              >
                取消
              </button>
              <button
                type="button"
                disabled={isRunning || migrating || (targetMode === "custom" && !customPath.trim())}
                onClick={handleExecuteRelocate}
                className={cn(
                  "motion-control px-5 h-8 rounded-full text-xs font-medium flex items-center justify-center gap-1.5 transition-all border disabled:opacity-40",
                  isLight
                    ? "bg-black/[0.08] border-black/[0.10] text-[#1a1625] hover:bg-black/[0.14] active:bg-black/[0.18]"
                    : "bg-white/20 border-white/15 text-white hover:bg-white/30 active:bg-white/35"
                )}
              >
                {migrating ? (
                  <>
                    <Loader2 className="w-3.5 h-3.5 animate-spin" />
                    {progressInfo ? `正在迁移 ${progressInfo.percent}%` : "正在准备迁移..."}
                  </>
                ) : (
                  "开始无损迁移"
                )}
              </button>
            </>
          )}
        </div>
      </div>
    </>
  );
};
