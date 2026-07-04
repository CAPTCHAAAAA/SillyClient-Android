import { createFileRoute } from "@tanstack/react-router";
import { useState, useEffect, useRef, useCallback } from "react";
import {
  Menu,
  ChevronDown,
  Check,
  X,
  Play,
  Search,
  Folder,
  Cloud,
  MoreVertical,
  ChevronLeft,
  ChevronRight,
  Moon,
  Sun,
  Image as ImageIcon,
  Terminal,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { TarvenEnv, DEFAULT_CONFIG } from "@/capacitor-plugin";
import type { InstanceConfig } from "@/capacitor-plugin";

export const Route = createFileRoute("/")({
  component: SillyClientLauncher,
});

interface TavernInstance {
  id: string;
  name: string;
  subtitle?: string;
  version?: string;
  status: "running" | "stopped" | "error" | "online" | "offline";
  type: "local" | "remote";
  lastUsed?: string;
  createdAt?: string;
  totalUsage?: string;
  icon: React.ReactNode;
  color: string;
  /** 本地实例监听端口(type=local 时有效),默认 8000 */
  port?: number;
  /** 远程实例地址(type=remote 时有效) */
  url?: string;
  /** 安装目录标识(本地实例,用于多实例隔离) */
  installDir?: string;
  /** GitHub release zipball 下载地址(本地实例首次安装时下载) */
  zipballUrl?: string;
  /** 自定义封面图片路径(更换插图) */
  cover?: string;
  /** 本地实例运行配置(映射管理面板设置) */
  config?: InstanceConfig;
}

const INSTANCES_KEY = "sillyclient.instances";
const INSTANCES_VERSION_KEY = "sillyclient.instances.version";
const CURRENT_VERSION = 2;

/** 从 localStorage 读取已持久化的实例列表;版本不匹配时清空旧数据。 */
function loadInstances(): TavernInstance[] {
  // 版本不匹配说明是旧版残留数据,清空
  const savedVersion = localStorage.getItem(INSTANCES_VERSION_KEY);
  if (savedVersion !== String(CURRENT_VERSION)) {
    localStorage.removeItem(INSTANCES_KEY);
    localStorage.setItem(INSTANCES_VERSION_KEY, String(CURRENT_VERSION));
    return [];
  }
  try {
    const raw = localStorage.getItem(INSTANCES_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as TavernInstance[];
      // icon 在持久化时无法存为 ReactNode,这里按 type 还原为图标节点
      return parsed.map((t) => ({
        ...t,
        icon: t.type === "local" ? <Folder className="w-5 h-5" /> : <Cloud className="w-5 h-5" />,
      }));
    }
  } catch {
    /* ignore */
  }
  return [];
}

/** 持久化实例列表(icon 不持久化,加载时还原)。 */
function saveInstances(list: TavernInstance[]) {
  try {
    const storable = list.map(({ icon: _icon, ...rest }) => rest);
    localStorage.setItem(INSTANCES_KEY, JSON.stringify(storable));
  } catch {
    /* ignore */
  }
}

type BgMode = "dynamic" | "custom";
type ThemeStyle = "dark" | "light";

function SillyClientLauncher() {
  const [instances, setInstances] = useState<TavernInstance[]>(loadInstances);
  const [showBgPanel, setShowBgPanel] = useState(false);
  const [isPanelClosing, setIsPanelClosing] = useState(false);
  const [bgMode, setBgMode] = useState<BgMode>("dynamic");
  const [dynamicPaused, setDynamicPaused] = useState(false);
  const [themeStyle, setThemeStyle] = useState<ThemeStyle>("dark");
  const [customWallpaperUrl, setCustomWallpaperUrl] = useState<string | null>(null);
  const wallpaperInputRef = useRef<HTMLInputElement>(null);
  const [showTerminal, setShowTerminal] = useState(false);
  const [isTerminalClosing, setIsTerminalClosing] = useState(false);
  const [terminalSize, setTerminalSize] = useState({ w: 640, h: 340 });
  const [terminalFontSize, setTerminalFontSize] = useState(12);
  const [terminalLogs, setTerminalLogs] = useState<{ msg: string; level?: string }[]>([
    { msg: "SillyClient Launcher 就绪,选择实例并点击启动", level: "info" },
  ]);
  const [launchingId, setLaunchingId] = useState<string | null>(null);

  // Logo 字体切换
  const logoFonts = [
    { name: 'Yummy', family: "'Yummy', sans-serif" },
    { name: 'Arcade Raiders', family: "'Arcade Raiders', sans-serif" },
    { name: 'Noisy Walk', family: "'Noisy Walk', sans-serif" },
    { name: 'Stay Pixel', family: "'Stay Pixel', sans-serif" },
    { name: '04B 30', family: "'04B 30', sans-serif" },
    { name: 'Pixel Chaos', family: "'Pixel Chaos', sans-serif" },
    { name: 'Soap', family: "'Soap', sans-serif" },
    { name: 'Syndra', family: "'Syndra', sans-serif" },
    { name: 'Dynamic Display', family: "'Dynamic Display', sans-serif" },
  ];
  const [logoFontIndex, setLogoFontIndex] = useState(0);

  // 实例卡片状态
  const [activeCardMenu, setActiveCardMenu] = useState<string | null>(null);
  const [isCardMenuClosing, setIsCardMenuClosing] = useState(false);
  const [showManagePanel, setShowManagePanel] = useState<TavernInstance | null>(null);
  const [isManagePanelClosing, setIsManagePanelClosing] = useState(false);
  const [manageTab, setManageTab] = useState("general");
  const [showAppMenu, setShowAppMenu] = useState(false);
  const [isAppMenuClosing, setIsAppMenuClosing] = useState(false);
  const [hoveredCard, setHoveredCard] = useState<string | null>(null);
  const [activeSlide, setActiveSlide] = useState(0);
  const [showNewInstancePanel, setShowNewInstancePanel] = useState(false);
  const [isNewInstancePanelClosing, setIsNewInstancePanelClosing] = useState(false);
  const [newInstanceMode, setNewInstanceMode] = useState<"local" | "remote">("local");
  const [newInstanceName, setNewInstanceName] = useState("");
  const [newInstanceDir, setNewInstanceDir] = useState("");
  const [newInstanceUrl, setNewInstanceUrl] = useState("http://");
  const [newInstanceVersion, setNewInstanceVersion] = useState("stable");
  // GitHub releases 真实数据
  const [releases, setReleases] = useState<{ tag: string; name: string; zipballUrl: string; prerelease: boolean }[]>([]);
  const [fetchingReleases, setFetchingReleases] = useState(false);
  // 选中的 release tag + zipballUrl(供 provision 下载)
  const [newInstanceZipUrl, setNewInstanceZipUrl] = useState<string | undefined>(undefined);
  // 搜索
  const [searchQuery, setSearchQuery] = useState("");
  // 终端输入
  const [terminalInput, setTerminalInput] = useState("");
  // 关于页真实数据
  const [aboutInfo, setAboutInfo] = useState<{ version: string; path: string; sizeBytes: number; createdAt: string; status: string } | null>(null);
  // 安全 insets(挖孔避让)
  const [safeInsetTop, setSafeInsetTop] = useState(0);
  // APP 设置:下拉刷新
  const [pullToRefresh, setPullToRefresh] = useState(false);
  const [verDropdownOpen, setVerDropdownOpen] = useState(false);
  const [verDropdownClosing, setVerDropdownClosing] = useState(false);
  const [verDropdownPos, setVerDropdownPos] = useState({ top: 0, left: 0, width: 0 });
  const carouselRef = useRef<HTMLDivElement>(null);
  const terminalBtnRef = useRef<HTMLButtonElement>(null);
  const [menuPos, setMenuPos] = useState({ top: 0, left: 0 });
  const [terminalPos, setTerminalPos] = useState({ left: 16 });

  const isLight = bgMode === "custom" && themeStyle === "light";

  // 轮播滚动到指定卡片（居中）
  const scrollToSlide = useCallback((index: number) => {
    const el = carouselRef.current;
    if (!el) return;
    const cards = el.querySelectorAll('[data-card-index]');
    const target = cards[index] as HTMLElement | undefined;
    if (!target) return;
    const cardWidth = 240;
    const containerWidth = el.clientWidth;
    const scrollLeft = target.offsetLeft - (containerWidth - cardWidth) / 2;
    el.scrollTo({ left: scrollLeft, behavior: 'smooth' });
    setActiveSlide(index);
  }, []);

  // 轮播拖拽 + 滚动指示器联动
  const dragState = useRef<{ isDown: boolean; startX: number; scrollLeft: number }>({ isDown: false, startX: 0, scrollLeft: 0 });

  useEffect(() => {
    const el = carouselRef.current;
    if (!el) return;

    const onDown = (e: MouseEvent | TouchEvent) => {
      const x = 'touches' in e ? e.touches[0].pageX : e.pageX;
      dragState.current = { isDown: true, startX: x - el.offsetLeft, scrollLeft: el.scrollLeft };
      el.style.cursor = 'grabbing';
      el.style.scrollSnapType = 'none';
    };
    const onMove = (e: MouseEvent | TouchEvent) => {
      if (!dragState.current.isDown) return;
      // 仅鼠标桌面端手动跟随(1:1);触屏交给原生滚动以保证跟手流畅
      if ('touches' in e) return;
      e.preventDefault();
      const x = e.pageX;
      const walk = (x - el.offsetLeft - dragState.current.startX);
      el.scrollLeft = dragState.current.scrollLeft - walk;
    };
    const onUp = () => {
      dragState.current.isDown = false;
      el.style.cursor = 'grab';
      el.style.scrollSnapType = 'x mandatory';
    };
    const onLeave = () => {
      if (dragState.current.isDown) onUp();
    };

    // 滚动时更新指示器
    const updateIndicator = () => {
      const containerWidth = el.clientWidth;
      const containerCenter = el.scrollLeft + containerWidth / 2;
      const cards = el.querySelectorAll('[data-card-index]');
      let closestIdx = 0;
      let closestDist = Infinity;
      cards.forEach((card) => {
        const center = (card as HTMLElement).offsetLeft + 120;
        const dist = Math.abs(center - containerCenter);
        if (dist < closestDist) {
          closestDist = dist;
          closestIdx = parseInt((card as HTMLElement).dataset.cardIndex || '0');
        }
      });
      setActiveSlide(closestIdx);
    };

    let scrollTimer: ReturnType<typeof setTimeout>;
    const onScroll = () => { clearTimeout(scrollTimer); scrollTimer = setTimeout(updateIndicator, 80); };

    el.style.cursor = 'grab';
    el.addEventListener('mousedown', onDown);
    el.addEventListener('mousemove', onMove);
    el.addEventListener('mouseup', onUp);
    el.addEventListener('mouseleave', onLeave);
    el.addEventListener('touchstart', onDown, { passive: true });
    el.addEventListener('touchmove', onMove, { passive: true });
    el.addEventListener('touchend', onUp, { passive: true });
    el.addEventListener('scroll', onScroll);

    return () => {
      clearTimeout(scrollTimer);
      el.removeEventListener('mousedown', onDown);
      el.removeEventListener('mousemove', onMove);
      el.removeEventListener('mouseup', onUp);
      el.removeEventListener('mouseleave', onLeave);
      el.removeEventListener('touchstart', onDown);
      el.removeEventListener('touchmove', onMove);
      el.removeEventListener('touchend', onUp);
      el.removeEventListener('scroll', onScroll);
    };
  }, []);

  // 自检:启动时扫描本地已存在的酒馆实例,自动添加卡片
  useEffect(() => {
    (async () => {
      try {
        const { instances } = await TarvenEnv.scanInstances();
        if (instances.length === 0) return;
        setInstances(prev => {
          // 合并:已存在的不重复添加
          const existingIds = new Set(prev.map(t => t.installDir || t.id));
          const scanned = instances
            .filter(s => !existingIds.has(s.instanceId))
            .map<TavernInstance>(s => ({
              id: `scan-${s.instanceId}`,
              name: "SillyTavern",
              subtitle: s.instanceId,
              version: s.version === "unknown" ? "—" : `v${s.version}`,
              status: s.hasServer ? "stopped" : "error",
              type: "local",
              lastUsed: "—",
              createdAt: "—",
              totalUsage: s.sizeBytes > 0 ? `${(s.sizeBytes / 1024 / 1024).toFixed(0)}MB` : "—",
              icon: <Folder className="w-5 h-5" />,
              color: "#9ca3af",
              port: 8000,
              installDir: s.instanceId,
              config: { ...DEFAULT_CONFIG },
            }));
          return [...scanned, ...prev];
        });
      } catch { /* 非 Capacitor 环境 */ }
    })();
  }, []);

  // 安全 insets(挖孔避让) — 原生返回物理像素,需除以 devicePixelRatio 转为 CSS 像素
  useEffect(() => {
    (async () => {
      try {
        const insets = await TarvenEnv.getSafeInsets();
        const dpr = window.devicePixelRatio || 1;
        setSafeInsetTop(Math.round(insets.top / dpr));
      } catch { /* 非 Capacitor 环境 */ }
    })();
  }, []);

  // 管理面板打开且切到关于页时,拉取真实实例数据
  useEffect(() => {
    if (!showManagePanel || manageTab !== "about") return;
    const t = showManagePanel;
    setAboutInfo(null);
    (async () => {
      try {
        if (t.type === "local") {
          const info = await TarvenEnv.getInstanceInfo({ instanceId: t.installDir || t.id, port: t.port ?? 8000 });
          setAboutInfo({ version: info.version, path: info.path, sizeBytes: info.sizeBytes, createdAt: info.createdAt, status: info.status });
        }
      } catch { /* 远程或非 Capacitor */ }
    })();
  }, [showManagePanel, manageTab]);

  const toggleBgPanel = () => {
    if (showBgPanel) {
      setIsPanelClosing(true);
      setTimeout(() => { setShowBgPanel(false); setIsPanelClosing(false); }, 250);
    } else {
      setShowBgPanel(true);
    }
  };

  const handleWallpaperUpload = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) setCustomWallpaperUrl(URL.createObjectURL(file));
  };

  useEffect(() => { document.documentElement.classList.add('dark'); }, []);

  // 实例列表持久化到 localStorage
  useEffect(() => { saveInstances(instances); }, [instances]);

  // 监听原生插件事件:日志 / 就绪 / 模式变化
  useEffect(() => {
    let logHandle: any, readyHandle: any, modeHandle: any, progressHandle: any;
    (async () => {
      try {
        logHandle = await TarvenEnv.addListener("log", (d: { message: string; level?: string }) => {
          setTerminalLogs(prev => [...prev, { msg: d.message, level: d.level }]);
        });
        progressHandle = await TarvenEnv.addListener("progress", (d: { percent: number; stage?: string }) => {
          const msg = d.stage ? `${d.stage} ${d.percent}%` : `${d.percent}%`;
          setTerminalLogs(prev => {
            // 合并连续进度行,避免刷屏
            const last = prev[prev.length - 1];
            if (last && last.level === "info" && /\d+%$/.test(last.msg)) {
              return [...prev.slice(0, -1), { msg, level: "info" }];
            }
            return [...prev, { msg, level: "info" }];
          });
        });
        readyHandle = await TarvenEnv.addListener("ready", (d: { url?: string; port?: number }) => {
          setTerminalLogs(prev => [...prev, { msg: `✓ 服务就绪${d.url ? " " + d.url : ""}`, level: "success" }]);
        });
        modeHandle = await TarvenEnv.addListener("mode", (d: { mode: string }) => {
          // 退回启动器时,把本地 running 实例置为 stopped
          if (d.mode === "launcher") {
            setInstances(prev => prev.map(t => t.status === "running" && t.type === "local" ? { ...t, status: "stopped" } : t));
          }
        });
      } catch { /* 非 Capacitor 原生环境,忽略 */ }
    })();
    return () => {
      logHandle?.remove?.();
      progressHandle?.remove?.();
      readyHandle?.remove?.();
      modeHandle?.remove?.();
    };
  }, []);

  // 启动实例:本地走 provisionAndStart(port)→enterImmersive;远程直接 enterImmersive(url)
  const launchTavern = useCallback(async (instance: TavernInstance) => {
    if (launchingId) return;
    setLaunchingId(instance.id);
    setInstances(prev => prev.map(t => t.id === instance.id ? { ...t, status: "running" } : t));
    setTerminalLogs(prev => [...prev, { msg: `> 启动实例 ${instance.name} (${instance.type})`, level: "info" }]);
    try {
      if (instance.type === "local") {
        const port = instance.port ?? 8000;
        const instanceId = instance.installDir || instance.id;
        const version = instance.version || "stable";
        const config = instance.config ?? DEFAULT_CONFIG;
        const zipballUrl = instance.zipballUrl;
        setTerminalLogs(prev => [...prev, { msg: `> 准备本地 Node 环境 [${instanceId}] 端口 ${port}...`, level: "info" }]);

        // 1. 注册 ready 事件监听
        let readyHandle: any;
        let readyReceived = false;
        const readyPromise = new Promise<void>((resolve) => {
          TarvenEnv.addListener("ready", (d: { ready?: boolean }) => {
            if (readyReceived) return;
            if (d.ready !== false) { readyReceived = true; resolve(); }
          }).then((h: any) => { readyHandle = h; });
        });

        // 2. 调用原生 provision(下载+启动)
        await TarvenEnv.provisionAndStart({ port, instanceId, version, zipballUrl, config });

        // 3. 等待 ready 事件,同时用 getStatus 轮询兜底
        let statusCheckInterval: any;
        const statusPromise = new Promise<void>((resolve) => {
          statusCheckInterval = setInterval(async () => {
            try {
              const s = await TarvenEnv.getStatus();
              if (s.serverReady && !readyReceived) { readyReceived = true; resolve(); }
            } catch { /* ignore */ }
          }, 2000);
        });

        // 4. 超时兜底(已有实例 60s, 首次下载 180s)
        const isFirstInstall = !zipballUrl;
        const timeoutMs = isFirstInstall ? 180000 : 60000;
        const timeoutPromise = new Promise<void>((resolve) => {
          setTimeout(() => { if (!readyReceived) resolve(); }, timeoutMs);
        });

        await Promise.race([readyPromise, statusPromise, timeoutPromise]);
        clearInterval(statusCheckInterval);
        readyHandle?.remove?.();

        if (!readyReceived) {
          setTerminalLogs(prev => [...prev, { msg: `> 启动超时: ${isFirstInstall ? '下载/解压耗时过长' : '服务未在预期时间内就绪'}`, level: "error" }]);
          setInstances(prev => prev.map(t => t.id === instance.id ? { ...t, status: "error" } : t));
          return;
        }

        setTerminalLogs(prev => [...prev, { msg: `> 本地服务就绪,进入沉浸式`, level: "success" }]);
        await TarvenEnv.enterImmersive({ url: `http://127.0.0.1:${port}/` });
      } else {
        const url = instance.url || "http://127.0.0.1:8000";
        setTerminalLogs(prev => [...prev, { msg: `> 连接远程实例 ${url}`, level: "info" }]);
        await TarvenEnv.enterImmersive({ url });
      }
    } catch (err: any) {
      const msg = err?.message || String(err);
      setTerminalLogs(prev => [...prev, { msg: `> 启动失败: ${msg}`, level: "error" }]);
      setInstances(prev => prev.map(t => t.id === instance.id ? { ...t, status: "error" } : t));
    } finally {
      setLaunchingId(null);
    }
  }, [launchingId]);

  /** 终端拖拽调整大小(同时支持鼠标与触屏)。 */
  const startResize = (clientX: number, clientY: number) => {
    const startX = clientX;
    const startY = clientY;
    const startW = terminalSize.w;
    const startH = terminalSize.h;
    const onMove = (mx: number, my: number) => {
      const newW = Math.min(Math.max(startW + mx - startX, 320), window.innerWidth - 32);
      const newH = Math.min(Math.max(startH + my - startY, 200), window.innerHeight - 112);
      setTerminalSize({ w: newW, h: newH });
    };
    const onMouseMove = (ev: MouseEvent) => onMove(ev.clientX, ev.clientY);
    const onMouseUp = () => {
      document.removeEventListener('mousemove', onMouseMove);
      document.removeEventListener('mouseup', onMouseUp);
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
    };
    const onTouchMove = (ev: TouchEvent) => { const t = ev.touches[0]; onMove(t.clientX, t.clientY); };
    const onTouchEnd = () => {
      document.removeEventListener('touchmove', onTouchMove);
      document.removeEventListener('touchend', onTouchEnd);
      document.body.style.userSelect = '';
    };
    document.body.style.cursor = 'se-resize';
    document.body.style.userSelect = 'none';
    document.addEventListener('mousemove', onMouseMove);
    document.addEventListener('mouseup', onMouseUp);
    document.addEventListener('touchmove', onTouchMove, { passive: false });
    document.addEventListener('touchend', onTouchEnd);
  };

  const getStatusText = (status: TavernInstance["status"]) => {
    switch (status) {
      case "running": return "运行中";
      case "stopped": return "已停止";
      case "error": return "错误";
      case "online": return "在线";
      case "offline": return "离线";
    }
  };

  const closeCardMenu = useCallback(() => {
    setIsCardMenuClosing(true);
    setTimeout(() => { setActiveCardMenu(null); setIsCardMenuClosing(false); }, 200);
  }, []);

  // 管理面板子组件
  function ManageItem({ label, desc, children }: { label: string; desc: string; children: React.ReactNode }) {
    return (
      <div className={cn("flex items-center justify-between gap-4 py-3 border-b", isLight ? "border-black/[0.04]" : "border-white/[0.04]")}>
        <div className="flex-1 min-w-0">
          <div className={cn("text-xs font-medium mb-0.5", isLight ? "text-[#1a1625]/75" : "text-white/75")}>{label}</div>
          <div className={cn("text-[10px] leading-snug", isLight ? "text-[#1a1625]/30" : "text-white/30")}>{desc}</div>
        </div>
        <div className="flex-shrink-0">{children}</div>
      </div>
    );
  }

  function ToggleSwitch({ defaultOn = false, on, onChange }: { defaultOn?: boolean; on?: boolean; onChange?: (v: boolean) => void }) {
    const [internal, setInternal] = useState(defaultOn);
    const isControlled = on !== undefined;
    const value = isControlled ? on! : internal;
    return (
      <button
        onClick={() => { if (!isControlled) setInternal(!internal); onChange?.(!value); }}
        className={cn(
          "relative w-10 h-[22px] rounded-full transition-colors duration-200",
          value
            ? isLight ? "bg-[#1a1625]/60" : "bg-white/30"
            : isLight ? "bg-black/[0.08]" : "bg-white/[0.08]"
        )}
      >
        <div className={cn(
          "absolute top-[2px] w-[18px] h-[18px] rounded-full shadow-sm transition-all duration-200",
          value ? "left-[calc(100%-20px)]" : "left-[2px]",
          isLight
            ? "bg-white shadow-black/10"
            : value ? "bg-white shadow-white/20" : "bg-white/50 shadow-black/15"
        )} />
      </button>
    );
  }

  /** 更新当前管理面板实例的 config 字段。 */
  const updateManagedConfig = (patch: Partial<InstanceConfig>) => {
    if (!showManagePanel) return;
    setInstances(prev => prev.map(t => t.id === showManagePanel.id ? { ...t, config: { ...(t.config ?? DEFAULT_CONFIG), ...patch } } : t));
  };

  function AppMenuItem({ label, desc, children }: { label: string; desc: string; children: React.ReactNode }) {
    return (
      <div className={cn("flex items-center justify-between gap-4 py-3 border-b last:border-b-0", isLight ? "border-black/[0.04]" : "border-white/[0.04]")}>
        <div className="flex-1 min-w-0">
          <div className={cn("text-xs font-medium mb-0.5", isLight ? "text-[#1a1625]/70" : "text-white/70")}>{label}</div>
          <div className={cn("text-[10px] leading-snug", isLight ? "text-[#1a1625]/30" : "text-white/30")}>{desc}</div>
        </div>
        <div className="flex-shrink-0">{children}</div>
      </div>
    );
  }

  function NewInstanceField({ label, desc, children }: { label: string; desc: string; children: React.ReactNode }) {
    return (
      <div>
        <div className={cn("text-xs font-medium mb-1", isLight ? "text-[#1a1625]/70" : "text-white/70")}>{label}</div>
        <div className={cn("text-[10px] mb-2", isLight ? "text-[#1a1625]/30" : "text-white/30")}>{desc}</div>
        {children}
      </div>
    );
  }

  return (
    <div className={cn(
      "min-h-screen overflow-hidden transition-colors duration-500",
      isLight ? "bg-[#f0ece8] text-[#1a1625]" : "bg-[#1a1625] text-white"
    )}>
      {/* 动态背景光效 */}
      {bgMode === "dynamic" && (
        <div className={cn("ambient-glow-container", dynamicPaused && "ambient-paused")}>
          <div className="ambient-glow ambient-glow-1" />
          <div className="ambient-glow ambient-glow-2" />
          <div className="ambient-glow ambient-glow-3" />
          <div className="ambient-glow ambient-glow-4" />
          <div className="ambient-glow ambient-glow-5" />
        </div>
      )}

      {/* 自定义壁纸 */}
      {bgMode === "custom" && customWallpaperUrl && (
        <div className="fixed inset-0 z-0 bg-cover bg-center bg-no-repeat" style={{ backgroundImage: `url(${customWallpaperUrl})` }} />
      )}

      <input ref={wallpaperInputRef} type="file" accept="image/*" className="hidden" onChange={handleWallpaperUpload} />

      {/* 背景设置面板 */}
      {(showBgPanel || isPanelClosing) && (
        <div className={cn(
          "fixed top-[5.5rem] left-1/2 -translate-x-1/2 z-[55] w-72 border backdrop-blur-[40px] saturate-180 rounded-[var(--radius-3xl)]",
          isLight ? "bg-white/60 border-black/5 shadow-[0_4px_16px_rgba(0,0,0,0.06)]" : "glass-panel",
          isPanelClosing ? "bg-panel-exit" : "bg-panel-enter"
        )}>
          <div className="p-4 space-y-4">
            <div className="flex items-center justify-between">
              <span className={cn("text-sm font-semibold", isLight ? "text-[#1a1625]" : "text-white/90")}>背景设置</span>
              <button onClick={toggleBgPanel} className={cn("p-1 rounded-lg transition-colors", isLight ? "hover:bg-black/5 text-[#1a1625]/60" : "hover:bg-white/5 text-white/40")}>
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="space-y-1.5">
              <span className={cn("text-xs font-medium", isLight ? "text-[#1a1625]/50" : "text-white/40")}>背景模式</span>
              <div className="grid grid-cols-2 gap-1.5">
                <button onClick={() => setBgMode("dynamic")} className={cn(
                  "px-2 py-2 rounded-lg text-xs font-medium transition-all border",
                  bgMode === "dynamic"
                    ? isLight ? "bg-black/10 border-black/20 text-[#1a1625]" : "bg-white/10 border-white/20 text-white/90"
                    : isLight ? "bg-black/5 border-black/10 text-[#1a1625]/60 hover:bg-black/10" : "bg-white/5 border-white/10 text-white/60 hover:bg-white/10"
                )}>基础</button>
                <button onClick={() => setBgMode("custom")} className={cn(
                  "px-2 py-2 rounded-lg text-xs font-medium transition-all border",
                  bgMode === "custom"
                    ? isLight ? "bg-black/10 border-black/20 text-[#1a1625]" : "bg-white/10 border-white/20 text-white/90"
                    : isLight ? "bg-black/5 border-black/10 text-[#1a1625]/60 hover:bg-black/10" : "bg-white/5 border-white/10 text-white/60 hover:bg-white/10"
                )}>自定义</button>
              </div>
            </div>

            {bgMode === "dynamic" && (
              <div className="flex items-center justify-between py-1">
                <span className={cn("text-xs font-medium", isLight ? "text-[#1a1625]" : "text-white/90")}>动态壁纸</span>
                <button onClick={() => setDynamicPaused(!dynamicPaused)} className="ios-toggle" aria-label="切换动态壁纸">
                  <div className={cn("ios-toggle-track", !dynamicPaused && "ios-toggle-track-active")}>
                    <div className="ios-toggle-icons">
                      <span className="ios-toggle-icon-off">○</span>
                      <span className="ios-toggle-icon-on">│</span>
                    </div>
                    <div className={cn("ios-toggle-thumb", !dynamicPaused && "ios-toggle-thumb-active")} />
                  </div>
                </button>
              </div>
            )}

            {bgMode === "custom" && (
              <div className="space-y-3">
                <div className="space-y-1.5">
                  <span className={cn("text-xs font-medium", isLight ? "text-[#1a1625]/50" : "text-white/40")}>主题风格</span>
                  <div className="grid grid-cols-2 gap-2">
                    <button onClick={() => setThemeStyle("dark")} className={cn(
                      "flex items-center justify-center gap-2 px-3 py-2.5 rounded-xl text-xs font-medium transition-all border",
                      themeStyle === "dark"
                        ? "bg-indigo-500/20 border-indigo-500/40 text-indigo-300"
                        : isLight ? "bg-black/5 border-black/10 text-[#1a1625]/60 hover:bg-black/10" : "bg-white/5 border-white/10 text-white/60 hover:bg-white/10"
                    )}><Moon className="w-3.5 h-3.5" /> 暗夜</button>
                    <button onClick={() => setThemeStyle("light")} className={cn(
                      "flex items-center justify-center gap-2 px-3 py-2.5 rounded-xl text-xs font-medium transition-all border",
                      themeStyle === "light"
                        ? isLight ? "bg-black/10 border-black/20 text-[#1a1625]" : "bg-white/10 border-white/20 text-white/90"
                        : isLight ? "bg-black/5 border-black/10 text-[#1a1625]/60 hover:bg-black/10" : "bg-white/5 border-white/10 text-white/60 hover:bg-white/10"
                    )}><Sun className="w-3.5 h-3.5" /> 白天</button>
                  </div>
                </div>

                <div className="space-y-1.5">
                  <span className={cn("text-xs font-medium", isLight ? "text-[#1a1625]/50" : "text-white/40")}>壁纸图片</span>
                  <button onClick={() => wallpaperInputRef.current?.click()} className={cn(
                    "w-full flex items-center gap-3 px-3 py-3 rounded-xl text-left transition-all border",
                    isLight ? "bg-black/5 border-black/10 hover:bg-black/10 text-[#1a1625]" : "bg-white/5 border-white/10 hover:bg-white/10 text-white"
                  )}>
                    <div className={cn("w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0", customWallpaperUrl ? "bg-emerald-500/20 text-emerald-400" : isLight ? "bg-black/10 text-[#1a1625]/50" : "bg-white/10 text-white/50")}>
                      {customWallpaperUrl ? <Check className="w-4 h-4" /> : <ImageIcon className="w-4 h-4" />}
                    </div>
                    <div className="min-w-0 flex-1">
                      <div className={cn("text-xs font-medium", isLight ? "text-[#1a1625]" : "text-white/90")}>{customWallpaperUrl ? "已导入壁纸" : "导入本地图片"}</div>
                      <div className={cn("text-[10px] truncate", isLight ? "text-[#1a1625]/50" : "text-white/40")}>{customWallpaperUrl ? "点击更换" : "支持 JPG / PNG / WebP"}</div>
                    </div>
                  </button>
                  {customWallpaperUrl && (
                    <button onClick={() => setCustomWallpaperUrl(null)} className={cn(
                      "w-full px-3 py-2 rounded-lg text-[10px] font-medium transition-all border",
                      isLight ? "bg-red-500/10 border-red-500/20 text-red-500 hover:bg-red-500/15" : "bg-red-500/10 border-red-500/20 text-red-400 hover:bg-red-500/15"
                    )}>移除壁纸</button>
                  )}
                </div>
              </div>
            )}
          </div>
        </div>
      )}

      {/* 顶部导航 */}
      <header className="fixed left-0 right-0 z-50 px-4" style={{ top: `${safeInsetTop + 4}px` }}>
        <div className={cn("h-12 flex items-center px-3 rounded-[var(--radius-3xl)] border backdrop-blur-[40px] saturate-180 transition-colors", isLight ? "bg-white/60 border-black/5 shadow-[0_4px_16px_rgba(0,0,0,0.06)]" : "glass-panel")}>
          <div className="flex items-center gap-3 flex-shrink-0">
            <button
              ref={terminalBtnRef}
              onClick={() => {
                if (showTerminal) {
                  setIsTerminalClosing(true);
                  setTimeout(() => { setShowTerminal(false); setIsTerminalClosing(false); }, 300);
                } else {
                  const btn = terminalBtnRef.current;
                  if (btn) {
                    const rect = btn.getBoundingClientRect();
                    setTerminalPos({ left: rect.left });
                  }
                  setShowTerminal(true);
                }
              }}
              className={cn(
                "ios-glass-btn px-3 h-8 flex items-center justify-center text-xs font-medium transition-all",
                isLight ? "text-[#1a1625]/70" : "text-white/70"
              )}
            >
              <Terminal className="w-3.5 h-3.5" />
            </button>
          </div>

          <div className="flex-1 flex items-center justify-center">
            <button onClick={toggleBgPanel} className={cn("flex items-center gap-2 px-4 py-1.5 rounded-full transition-all max-w-[200px] border", isLight ? "hover:bg-black/5 border-black/10 shadow-[inset_0_1px_2px_rgba(0,0,0,0.05),0_1px_2px_rgba(255,255,255,0.5)]" : "hover:bg-white/10 border-white/10 shadow-[inset_0_1px_2px_rgba(0,0,0,0.2),0_1px_2px_rgba(255,255,255,0.1)]")}>
              <span className={cn("text-sm font-medium truncate", isLight ? "text-[#1a1625]" : "text-white")}>
                {new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}
              </span>
              <ChevronDown className={cn("w-4 h-4 flex-shrink-0 transition-transform", isLight ? "text-[#1a1625]/60" : "text-white/60", showBgPanel && "rotate-180")} />
            </button>
          </div>

          <button
            onClick={() => {
              if (showAppMenu) {
                setIsAppMenuClosing(true);
                setTimeout(() => { setShowAppMenu(false); setIsAppMenuClosing(false); }, 300);
              } else {
                setShowAppMenu(true);
              }
            }}
            className={cn(
            "ios-glass-btn px-4 h-9 flex items-center justify-center text-xs font-medium flex-shrink-0",
            isLight ? "text-[#1a1625]/70" : "text-white/70"
          )}>
            <Menu className="w-4 h-4" />
          </button>
        </div>
      </header>

      {/* 终端面板 */}
      {(showTerminal || isTerminalClosing) && (
        <div
          className={cn(
            "fixed z-[55] rounded-2xl backdrop-blur-[40px] saturate-180 flex flex-col overflow-hidden",
            isLight
              ? "bg-white/70 border border-black/[0.06] shadow-[0_8px_40px_rgba(0,0,0,0.08)]"
              : "bg-[#1a1625]/80 border border-white/[0.08] shadow-[0_8px_40px_rgba(0,0,0,0.3)]",
            isTerminalClosing ? "animate-terminal-exit" : "animate-terminal-enter"
          )}
          style={{
            top: '5.5rem',
            left: terminalPos.left,
            width: terminalSize.w,
            height: terminalSize.h,
            minWidth: 320,
            minHeight: 200,
            maxWidth: 'calc(100vw - 2rem)',
            maxHeight: 'calc(100vh - 7rem)',
          }}
        >
          {/* 标题栏 */}
          <div className={cn(
            "flex items-center justify-between px-4 h-9 flex-shrink-0 border-b",
            isLight ? "border-black/[0.06]" : "border-white/[0.06]"
          )}>
            <div className="flex items-center gap-2">
              <Terminal className={cn("w-3.5 h-3.5", isLight ? "text-[#1a1625]/40" : "text-white/40")} />
              <span className={cn("text-xs font-medium", isLight ? "text-[#1a1625]/50" : "text-white/50")}>终端</span>
            </div>
            <div className="flex items-center gap-2">
              {/* iOS 横向滚轮 — 字号调节 */}
              <div className={cn(
                "flex items-center gap-1.5 px-2 py-1 rounded-lg",
                isLight ? "bg-black/[0.04]" : "bg-white/[0.04]"
              )}>
                <span className={cn("text-[10px] tabular-nums w-6 text-right", isLight ? "text-[#1a1625]/35" : "text-white/35")}>{terminalFontSize}</span>
                <input
                  type="range"
                  min={9}
                  max={20}
                  step={1}
                  value={terminalFontSize}
                  onChange={(e) => setTerminalFontSize(Number(e.target.value))}
                  className={cn("ios-font-slider w-14 h-1 appearance-none bg-none cursor-pointer", isLight && "ios-font-slider-light")}
                />
                <span className={cn("text-[10px]", isLight ? "text-[#1a1625]/25" : "text-white/25")}>A</span>
              </div>
              <button
                onClick={() => {
                  setIsTerminalClosing(true);
                  setTimeout(() => { setShowTerminal(false); setIsTerminalClosing(false); }, 300);
                }}
                className={cn("p-1 rounded-md transition-colors", isLight ? "hover:bg-black/5 text-[#1a1625]/30" : "hover:bg-white/5 text-white/30")}
              >
                <X className="w-3.5 h-3.5" />
              </button>
            </div>
          </div>

          {/* 终端内容区 */}
          <div
            className={cn("flex-1 font-mono leading-relaxed p-4 overflow-y-auto scrollbar-subtle", isLight ? "bg-[#1e1e2e]/90 text-[#cdd6f4]" : "bg-[#0d0d14]/90 text-[#cdd6f4]")}
            style={{ fontSize: `${terminalFontSize}px` }}
          >
            <div className="opacity-50 mb-1">SillyClient Launcher v2.1.0</div>
            {terminalLogs.map((log, i) => (
              <div key={i} className={cn(
                "mb-0.5 whitespace-pre-wrap break-all",
                log.level === "error" ? "text-red-400" : log.level === "success" ? "text-emerald-400" : "opacity-80"
              )}>{log.msg}</div>
            ))}
            <div className="flex gap-2 mt-1">
              <span className="text-emerald-400/70 select-none">~ $</span>
              <input
                type="text"
                value={terminalInput}
                onChange={(e) => setTerminalInput(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" && terminalInput.trim()) {
                    const cmd = terminalInput.trim();
                    setTerminalLogs(prev => [...prev, { msg: `~ $ ${cmd}`, level: "info" }]);
                    TarvenEnv.sendCommand({ text: cmd }).catch(() => {});
                    setTerminalInput("");
                  }
                }}
                className="flex-1 bg-transparent outline-none text-[#cdd6f4] border-none"
                placeholder="输入命令(termux 式)"
                autoCapitalize="none"
                autoCorrect="off"
                spellCheck={false}
              />
            </div>
          </div>

          {/* 拖拽调整大小手柄 — 简约斜线标识 */}
          <div
            className={cn("absolute bottom-1 right-1 w-5 h-5 cursor-se-resize z-10 opacity-40 transition-opacity hover:opacity-80 touch-none", isLight ? "text-[#1a1625]" : "text-white")}
            onMouseDown={(e) => startResize(e.clientX, e.clientY)}
            onTouchStart={(e) => { e.preventDefault(); const t = e.touches[0]; startResize(t.clientX, t.clientY); }}
          >
            <svg viewBox="0 0 12 12" fill="none" className="w-full h-full">
              <line x1="11" y1="1" x2="5" y2="7" stroke="currentColor" strokeWidth="1" strokeLinecap="round" />
              <line x1="11" y1="4" x2="8" y2="7" stroke="currentColor" strokeWidth="1" strokeLinecap="round" />
              <line x1="11" y1="7" x2="11" y2="7" stroke="currentColor" strokeWidth="1" strokeLinecap="round" />
            </svg>
          </div>
        </div>
      )}

      {/* APP 菜单 */}
      {(showAppMenu || isAppMenuClosing) && (
        <>
          <div className={cn(
            "fixed inset-0 z-[56] bg-black/20 backdrop-blur-sm overlay-backdrop",
            isAppMenuClosing && "overlay-backdrop-exit"
          )} onClick={() => {
            setIsAppMenuClosing(true);
            setTimeout(() => { setShowAppMenu(false); setIsAppMenuClosing(false); }, 300);
          }} />
          <div className={cn(
            "fixed z-[58] rounded-2xl border backdrop-blur-[40px] saturate-180 flex flex-col overflow-hidden",
            isLight
              ? "bg-[#f5f3ef]/95 border-black/[0.08] shadow-[0_16px_60px_rgba(0,0,0,0.12)]"
              : "bg-[#1a1625]/90 border-white/[0.08] shadow-[0_16px_60px_rgba(0,0,0,0.4)]",
            isAppMenuClosing ? "animate-clone-panel-exit" : "animate-clone-panel"
          )} style={{
            top: '50%',
            left: '50%',
            transform: 'translate(-50%, -50%)',
            width: 'min(420px, calc(100vw - 2rem))',
            maxHeight: 'min(80vh, calc(100vh - 4rem))',
          }}>
            {/* 头部 */}
            <div className={cn("flex items-center justify-between px-5 h-12 flex-shrink-0 border-b", isLight ? "border-black/[0.06]" : "border-white/[0.06]")}>
              <span className={cn("text-sm font-semibold", isLight ? "text-[#1a1625]" : "text-white")}>APP 设置</span>
              <button onClick={() => {
                setIsAppMenuClosing(true);
                setTimeout(() => { setShowAppMenu(false); setIsAppMenuClosing(false); }, 300);
              }} className={cn("p-1.5 rounded-lg transition-colors", isLight ? "hover:bg-black/5 text-[#1a1625]/30" : "hover:bg-white/5 text-white/30")}>
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="flex-1 overflow-y-auto p-5 space-y-5 scrollbar-subtle">
              {/* 浏览器、服务与性能 */}
              <div>
                <div className={cn("text-[10px] font-semibold tracking-wide uppercase mb-3", isLight ? "text-[#1a1625]/25" : "text-white/25")}>浏览器、服务与性能</div>
                <div className="space-y-1">
                  <AppMenuItem label="下拉刷新" desc="在酒馆界面顶部下拉刷新 WebView">
                    <ToggleSwitch on={pullToRefresh} onChange={(v) => { setPullToRefresh(v); TarvenEnv.setPullToRefresh({ enabled: v }).catch(() => {}); }} />
                  </AppMenuItem>
                  <AppMenuItem label="Node 内存上限" desc="--max-old-space-size 的 V8 内存">
                    <span className={cn("text-[11px] font-medium px-2 py-1 rounded-lg", isLight ? "bg-black/[0.05] text-[#1a1625]/50" : "bg-white/[0.06] text-white/50")}>自动</span>
                  </AppMenuItem>
                  <AppMenuItem label="Node semi-space" desc="V8 新生代半空间大小，影响 GC 频率和峰值内存">
                    <span className={cn("text-[11px] font-medium px-2 py-1 rounded-lg", isLight ? "bg-black/[0.05] text-[#1a1625]/50" : "bg-white/[0.06] text-white/50")}>自动</span>
                  </AppMenuItem>
                </div>
              </div>

              {/* 常用操作 */}
              <div>
                <div className={cn("text-[10px] font-semibold tracking-wide uppercase mb-3", isLight ? "text-[#1a1625]/25" : "text-white/25")}>常用操作</div>
                <div className="space-y-2">
                  <button className={cn(
                    "w-full px-4 py-2.5 rounded-xl text-xs font-medium text-left transition-all border",
                    isLight ? "bg-black/[0.03] border-black/[0.06] text-[#1a1625]/60 hover:bg-black/[0.06] hover:text-[#1a1625]/80" : "bg-white/[0.03] border-white/[0.06] text-white/60 hover:bg-white/[0.06] hover:text-white/80"
                  )}>导入数据压缩包</button>
                  <button className={cn(
                    "w-full px-4 py-2.5 rounded-xl text-xs font-medium text-left transition-all border",
                    isLight ? "bg-black/[0.03] border-black/[0.06] text-[#1a1625]/60 hover:bg-black/[0.06] hover:text-[#1a1625]/80" : "bg-white/[0.03] border-white/[0.06] text-white/60 hover:bg-white/[0.06] hover:text-white/80"
                  )}>导出数据压缩包</button>
                  <button onClick={() => TarvenEnv.clearWebViewData().catch(() => {})} className={cn(
                    "w-full px-4 py-2.5 rounded-xl text-xs font-medium text-left transition-all border",
                    isLight ? "bg-black/[0.03] border-black/[0.06] text-[#1a1625]/60 hover:bg-black/[0.06] hover:text-[#1a1625]/80" : "bg-white/[0.03] border-white/[0.06] text-white/60 hover:bg-white/[0.06] hover:text-white/80"
                  )}>清空浏览器数据</button>
                </div>
              </div>

              {/* 危险操作 */}
              <div>
                <div className={cn("text-[10px] font-semibold tracking-wide uppercase mb-3", isLight ? "text-[#1a1625]/25" : "text-white/25")}>危险区域</div>
                <button onClick={() => { if (confirm("确认清空宿主数据并重新初始化?所有实例将被删除!")) { localStorage.removeItem("sillyclient.instances"); TarvenEnv.clearWebViewData().catch(() => {}); setInstances([]); } }} className={cn(
                  "w-full px-4 py-2.5 rounded-xl text-xs font-medium text-left transition-all border",
                  isLight ? "bg-red-50/50 border-red-900/10 text-red-900/50 hover:bg-red-50/80 hover:text-red-900/70" : "bg-red-400/[0.04] border-red-400/10 text-red-400/50 hover:bg-red-400/[0.08] hover:text-red-400/70"
                )}>
                  清空宿主数据并重新初始化
                </button>
              </div>
            </div>
          </div>
        </>
      )}

      {/* 主内容 */}
      <main className="pb-12 px-6 min-h-screen flex flex-col items-center" style={{ paddingTop: `${safeInsetTop + 68}px` }}>
        {/* Logo */}
        <div className="mb-4 text-center select-none cursor-pointer group" onClick={() => setLogoFontIndex(prev => (prev + 1) % logoFonts.length)} title={`点击切换字体 (${logoFonts[logoFontIndex].name})`}>
          <span
            className="inline-block text-[clamp(3rem,10vw,7.5rem)] font-normal leading-none transition-all duration-300"
            style={{
              fontFamily: logoFonts[logoFontIndex].family,
              letterSpacing: '0.03em',
            }}
          >
            <span style={{
              color: isLight ? '#a09b9e' : '#ffd2dc',
            }}>Silly</span>
            <span style={{
              color: '#e8365d',
            }}>Client</span>
          </span>
          <div className={cn(
            "text-[10px] opacity-0 group-hover:opacity-100 transition-opacity duration-300",
            isLight ? "text-[#1a1625]/25" : "text-white/25"
          )}>{logoFonts[logoFontIndex].name}</div>
        </div>

        {/* 搜索栏 */}
        <div className="relative mb-10 w-full max-w-2xl">
          <Search className={cn("absolute left-4 top-1/2 -translate-y-1/2 w-5 h-5", isLight ? "text-[#1a1625]/40" : "text-white/40")} />
          <input type="text" value={searchQuery} onChange={(e) => setSearchQuery(e.target.value)} onKeyDown={(e) => {
            if (e.key === "Enter") {
              const match = instances.find(t => (t.subtitle || t.name).toLowerCase().includes(searchQuery.toLowerCase()));
              if (match) {
                const idx = instances.indexOf(match) + 1; // +1 因为新建卡片在 index 0
                scrollToSlide(idx);
              }
            }
          }} placeholder="搜索并打开实例" className={cn(
            "w-full h-14 pl-12 pr-4 rounded-[20px] border focus:outline-none focus:border-[#1a1625]/30 transition-all",
            isLight ? "bg-black/5 border-black/10 text-[#1a1625] placeholder:text-[#1a1625]/40 focus:bg-black/8" : "bg-white/5 border-white/10 text-white placeholder:text-white/40 focus:bg-white/10"
          )} />
          {searchQuery && (
            <div className="absolute top-full left-0 right-0 mt-2 rounded-2xl border overflow-hidden z-30 max-h-64 overflow-y-auto scrollbar-subtle">
              {instances.filter(t => (t.subtitle || t.name).toLowerCase().includes(searchQuery.toLowerCase())).map(t => (
                <button key={t.id} onClick={() => { const idx = instances.indexOf(t) + 1; scrollToSlide(idx); setSearchQuery(""); }} className={cn(
                  "w-full px-4 py-3 text-left text-sm flex items-center gap-3 transition-colors",
                  isLight ? "bg-[#f5f3ef]/95 hover:bg-black/5 text-[#1a1625]/80" : "bg-[#1a1625]/95 hover:bg-white/10 text-white/80"
                )}>
                  <span className="scale-75">{t.icon}</span>
                  <span>{t.subtitle || t.name}</span>
                  <span className={cn("ml-auto text-[10px]", isLight ? "text-[#1a1625]/40" : "text-white/40")}>{t.type === "local" ? "本地" : "远程"}</span>
                </button>
              ))}
              {instances.filter(t => (t.subtitle || t.name).toLowerCase().includes(searchQuery.toLowerCase())).length === 0 && (
                <div className={cn("px-4 py-3 text-sm", isLight ? "bg-[#f5f3ef]/95 text-[#1a1625]/40" : "bg-[#1a1625]/95 text-white/40")}>无匹配实例</div>
              )}
            </div>
          )}
        </div>

        {/* 实例卡片轮播 */}
        <div className="w-full max-w-6xl mx-auto px-6 md:px-8">
          <div className="relative">
            <div ref={carouselRef} className="flex gap-5 overflow-x-auto snap-x snap-mandatory pb-3" style={{ scrollbarWidth: 'none', msOverflowStyle: 'none' }}>

              <div className="flex-shrink-0 w-[calc(50vw-120px)]" aria-hidden />

              {/* 新建实例 */}
              <button
                onClick={() => {
                  setNewInstanceMode("local");
                  setNewInstanceName("");
                  setNewInstanceDir("");
                  setNewInstanceUrl("http://");
                  setNewInstanceVersion("stable");
                  setShowNewInstancePanel(true);
                }}
                className={cn(
                  "flex-shrink-0 w-60 h-[320px] rounded-[18px] overflow-hidden snap-center transition-all duration-300 group relative",
                  isLight ? "bg-black/[0.03] border border-black/[0.08] hover:border-black/15" : "bg-white/[0.04] border border-white/[0.06] hover:border-white/15"
                )}
                data-card-index="0"
              >
                <div className="relative h-full flex flex-col justify-between p-3.5">
                  <div className={cn("w-8 h-8 rounded-lg flex items-center justify-center transition-transform duration-300 group-hover:scale-110", isLight ? "bg-black/[0.06]" : "bg-white/[0.08]")}>
                    <Play className={cn("w-3.5 h-3.5", isLight ? "text-[#1a1625]/40" : "text-white/40")} />
                  </div>
                  <div>
                    <div className={cn("text-base font-semibold mb-0.5", isLight ? "text-[#1a1625]" : "text-white")}>新建实例</div>
                    <div className={cn("text-xs", isLight ? "text-[#1a1625]/40" : "text-white/40")}>设置新的酒馆环境</div>
                  </div>
                </div>
              </button>

              {/* 实例卡片 */}
              {instances.map((instance, index) => (
                <div
                  key={instance.id}
                  data-card-index={String(index + 1)}
                  className={cn(
                    "flex-shrink-0 w-60 h-[320px] rounded-[18px] snap-center transition-all duration-500 ease-[cubic-bezier(0.22,1,0.36,1)] relative group border",
                    isLight
                      ? cn("border-black/[0.08]", hoveredCard === instance.id && "border-black/15 z-20", activeCardMenu === instance.id && "border-black/25 ring-1 ring-black/10 z-30")
                      : cn("border-white/[0.06]", hoveredCard === instance.id && "border-white/15 z-20", activeCardMenu === instance.id && "border-white/25 ring-1 ring-white/10 z-30")
                  )}
                  onClick={(e) => {
                    if ((e.target as HTMLElement).closest('button')) return;
                    setHoveredCard(hoveredCard === instance.id ? null : instance.id);
                  }}
                >
                  <div className="absolute inset-0 rounded-[20px] overflow-hidden">
                    <img src={instance.cover || "/tavern-logo.png"} alt="" className="w-full h-full object-cover" loading="lazy" />
                    <div className="absolute inset-0" style={{
                      background: isLight
                        ? 'linear-gradient(135deg, oklch(1 0 0 / 0.40) 0%, oklch(1 0 0 / 0.25) 100%)'
                        : 'oklch(0 0 0 / 0.5)'
                    }} />
                  </div>

                  <div className={cn(
                    "absolute inset-0 rounded-[20px] transition-opacity duration-700",
                    hoveredCard === instance.id
                      ? "bg-gradient-to-t from-black/80 via-black/50 to-black/20"
                      : isLight ? "bg-gradient-to-t from-white/70 via-white/35 to-white/5" : "bg-gradient-to-t from-black/75 via-black/40 to-black/10"
                  )} />

                  <div className="relative h-full flex flex-col p-3.5 overflow-hidden">
                    <span className={cn(
                      "self-start px-2 py-0.5 rounded-md text-[10px] font-semibold tracking-wide border backdrop-blur-md w-fit",
                      isLight ? "bg-black/[0.06] text-[#1a1625]/55 border-black/[0.08]" : "bg-white/[0.08] text-white/50 border-white/[0.08]"
                    )}>
                      {instance.version || "—"}
                    </span>

                    <div className="flex-1" />

                    <div className={cn("text-sm font-medium leading-snug mb-2", isLight ? "text-[#1a1625]/75" : "text-white/80")}>{instance.subtitle}</div>

                    <div className="flex items-center justify-between mt-1.5">
                      <div className="flex items-center gap-1.5">
                        <div className={cn("w-3.5 h-3.5 rounded flex items-center justify-center backdrop-blur-md shrink-0", isLight ? "bg-black/[0.08]" : "bg-white/10")}>
                          <span className={cn("scale-[0.7]", isLight ? "text-[#1a1625]/50" : "text-white")}>{instance.icon}</span>
                        </div>
                        <span className={cn("text-[10px] font-medium", isLight ? "text-[#1a1625]/45" : "text-white/50")}>
                          {instance.type === "local" ? "本地" : "远程"}
                        </span>
                      </div>
                      <div className="flex items-center gap-1">
                        <div className={cn("w-1.5 h-1.5 rounded-full",
                          instance.type === "local"
                            ? "bg-sky-400/70 shadow-[0_0_4px_sky-400/50]"
                            : instance.status === "online"
                              ? "bg-emerald-400/70 shadow-[0_0_4px_emerald-400/50]"
                              : "bg-red-400/60 shadow-[0_0_3px_red-400/40]"
                        )} />
                        <span className={cn("text-[10px] font-medium",
                          instance.type === "local"
                            ? "text-sky-400/80 shadow-[0_0_6px_sky-400/40]"
                            : instance.status === "online"
                              ? "text-emerald-400/80 shadow-[0_0_6px_emerald-400/40]"
                              : "text-red-400/60 shadow-[0_0_4px_red-400/30]"
                        )}>
                          {instance.type === "local" ? "本地" : getStatusText(instance.status)}
                        </span>
                      </div>
                    </div>

                    <div className="transition-all duration-700 ease-[cubic-bezier(0.22,1,0.36,1)]" style={{
                      maxHeight: hoveredCard === instance.id ? 200 : 0,
                      opacity: hoveredCard === instance.id ? 1 : 0,
                      overflow: 'hidden'
                    }}>
                      <div className={cn("pt-2 border-t space-y-1", isLight ? "border-black/[0.06]" : "border-white/10")}>
                        <div className="flex items-center justify-between text-[11px]">
                          <span className={cn(isLight ? "text-[#1a1625]/35" : "text-white/40")}>创建时间</span>
                          <span className={cn("font-medium tabular-nums", isLight ? "text-[#1a1625]/60" : "text-white/70")}>{instance.createdAt || "—"}</span>
                        </div>
                        <div className="flex items-center justify-between text-[11px]">
                          <span className={cn(isLight ? "text-[#1a1625]/35" : "text-white/40")}>上次使用</span>
                          <span className={cn("font-medium tabular-nums", isLight ? "text-[#1a1625]/60" : "text-white/70")}>{instance.lastUsed || "—"}</span>
                        </div>
                        <div className="flex items-center justify-between text-[11px]">
                          <span className={cn(isLight ? "text-[#1a1625]/35" : "text-white/40")}>累计使用</span>
                          <span className={cn("font-medium tabular-nums", isLight ? "text-[#1a1625]/60" : "text-white/70")}>{instance.totalUsage || "—"}</span>
                        </div>
                        <div className="flex items-center justify-between pt-1.5">
                          <button onClick={(e) => { e.stopPropagation(); launchTavern(instance); }} disabled={launchingId === instance.id} className={cn(
                            "h-7 px-4 rounded-full text-[11px] font-semibold flex items-center justify-center gap-1 transition-all active:scale-95 disabled:opacity-50",
                            isLight ? "bg-[#1a1625] text-[#f5f3ef] hover:bg-[#1a1625]/90" : "bg-white/90 text-[#1a1625] hover:bg-white"
                          )}>
                            <Play className="w-2.5 h-2.5" /> {launchingId === instance.id ? "启动中" : "启动"}
                          </button>
                          <button onClick={(e) => {
                            e.stopPropagation();
                            const id = instance.id;
                            if (activeCardMenu === id) {
                              setActiveCardMenu(null);
                            } else {
                              const r = e.currentTarget.getBoundingClientRect();
                              setMenuPos({ top: r.top + r.height / 2, left: r.left });
                              setActiveCardMenu(id);
                            }
                          }} className={cn(
                            "w-7 h-7 rounded-full backdrop-blur-md flex items-center justify-center transition-all active:scale-95",
                            isLight ? "bg-black/[0.06] hover:bg-black/10" : "bg-white/15 hover:bg-white/25"
                          )}>
                            <MoreVertical className={cn("w-3 h-3", isLight ? "text-[#1a1625]/50" : "text-white")} />
                          </button>
                        </div>
                      </div>
                    </div>

                  </div>

                  {/* 三点菜单 */}
                  {(activeCardMenu === instance.id || (isCardMenuClosing && activeCardMenu === instance.id)) && (
                    <>
                      <div className={cn(
                        "fixed inset-0 z-40 bg-black/20 backdrop-blur-sm overlay-backdrop",
                        isCardMenuClosing && "overlay-backdrop-exit"
                      )} onClick={closeCardMenu} />
                      <div className={cn(
                        "fixed z-50 w-44 py-1 px-1 rounded-2xl border shadow-2xl overflow-hidden",
                        isCardMenuClosing ? "animate-popover-exit" : "animate-popover",
                        isLight
                          ? "bg-[#f5f3ef]/95 backdrop-blur-xl border-black/[0.08] shadow-black/5"
                          : "bg-[#1a1625]/90 backdrop-blur-xl border-white/[0.08] shadow-black/30"
                      )} style={{
                        top: Math.max(Math.min(menuPos.top, window.innerHeight - 200), 16),
                        left: Math.max(Math.min(menuPos.left + 8, window.innerWidth - 192), 16),
                        transform: 'translateY(-50%)',
                      }}>
                        <button
                          onClick={(e) => { e.stopPropagation(); closeCardMenu(); setShowManagePanel(instance); }}
                          className={cn("w-full px-3 py-2.5 text-left text-sm transition-colors", isLight ? "text-[#1a1625]/50 hover:text-[#1a1625]/80" : "text-white/50 hover:text-white/80")}
                        >管理</button>
                        <button onClick={async (e) => {
                          e.stopPropagation();
                          closeCardMenu();
                          try {
                            const { path } = await TarvenEnv.pickImage({ instanceId: instance.installDir || instance.id });
                            setInstances(prev => prev.map(t => t.id === instance.id ? { ...t, cover: `file://${path}` } : t));
                          } catch { /* 取消或失败 */ }
                        }} className={cn("w-full px-3 py-2.5 text-left text-sm transition-colors", isLight ? "text-[#1a1625]/50 hover:text-[#1a1625]/80" : "text-white/50 hover:text-white/80")}>更换插图</button>
                        <div className={cn("my-1 h-px", isLight ? "bg-black/[0.06]" : "bg-white/[0.06]")} />
                        <button onClick={(e) => { e.stopPropagation(); closeCardMenu(); setInstances(prev => prev.filter(t => t.id !== instance.id)); }} className={cn("w-full px-3 py-2.5 text-left text-sm transition-colors", isLight ? "text-red-900/40 hover:text-red-900/70" : "text-red-400/40 hover:text-red-400/70")}>卸载</button>
                      </div>
                    </>
                  )}
                </div>
              ))}

              <div className="flex-shrink-0 w-[calc(50vw-120px)]" aria-hidden />
            </div>

            {/* 指示器 + 方向键 */}
            <div className="flex items-center justify-center gap-3 mt-4">
              <button onClick={() => scrollToSlide(Math.max(0, activeSlide - 1))} disabled={activeSlide === 0} className={cn(
                "w-7 h-7 rounded-full flex items-center justify-center transition-all active:scale-90",
                activeSlide === 0
                  ? isLight ? "text-[#1a1625]/15 cursor-default" : "text-white/15 cursor-default"
                  : isLight ? "text-[#1a1625]/40 hover:text-[#1a1625]/70 hover:bg-[#1a1625]/8" : "text-white/40 hover:text-white/70 hover:bg-white/10"
              )}><ChevronLeft className="w-3.5 h-3.5" /></button>

              {Array.from({ length: instances.length + 1 }).map((_, i) => (
                <button key={i} onClick={() => scrollToSlide(i)} className={cn("rounded-full transition-all duration-300",
                  i === activeSlide
                    ? isLight ? "bg-[#1a1625]/45 w-4 h-1.5" : "bg-white/50 w-4 h-1.5"
                    : isLight ? "bg-[#1a1625]/12 w-1.5 h-1.5 hover:bg-[#1a1625]/20" : "bg-white/15 w-1.5 h-1.5 hover:bg-white/25"
                )} />
              ))}

              <button onClick={() => scrollToSlide(Math.min(instances.length, activeSlide + 1))} disabled={activeSlide === instances.length} className={cn(
                "w-7 h-7 rounded-full flex items-center justify-center transition-all active:scale-90",
                activeSlide === instances.length
                  ? isLight ? "text-[#1a1625]/15 cursor-default" : "text-white/15 cursor-default"
                  : isLight ? "text-[#1a1625]/40 hover:text-[#1a1625]/70 hover:bg-[#1a1625]/8" : "text-white/40 hover:text-white/70 hover:bg-white/10"
              )}><ChevronRight className="w-3.5 h-3.5" /></button>
            </div>
          </div>
        </div>
      </main>

      {/* 新建实例面板 */}
      {(showNewInstancePanel || isNewInstancePanelClosing) && (
        <>
          <div className={cn(
            "fixed inset-0 z-[60] bg-black/20 backdrop-blur-sm overlay-backdrop",
            isNewInstancePanelClosing && "overlay-backdrop-exit"
          )} onClick={() => { setShowNewInstancePanel(false); setIsNewInstancePanelClosing(false); setVerDropdownOpen(false); }} />
          <div className={cn(
            "fixed z-[62] rounded-2xl border flex flex-col overflow-hidden",
            isNewInstancePanelClosing ? "animate-clone-panel-exit" : "animate-clone-panel",
            isLight
              ? "bg-[#f5f3ef]/95 border-black/[0.08] shadow-[0_16px_60px_rgba(0,0,0,0.12)]"
              : "bg-[#1a1625]/90 border-white/[0.08] shadow-[0_16px_60px_rgba(0,0,0,0.4)]"
          )} style={{
            top: '50%',
            left: '50%',
            transform: 'translate(-50%, -50%)',
            width: 'min(460px, calc(100vw - 2rem))',
            maxHeight: 'min(85vh, calc(100vh - 4rem))',
          }}>
            {/* 头部 */}
            <div className={cn("flex items-center justify-between px-5 h-12 flex-shrink-0 border-b", isLight ? "border-black/[0.06]" : "border-white/[0.06]")}>
              <span className={cn("text-sm font-semibold", isLight ? "text-[#1a1625]" : "text-white")}>新建实例</span>
              <button onClick={() => { setIsNewInstancePanelClosing(true); setTimeout(() => { setShowNewInstancePanel(false); setIsNewInstancePanelClosing(false); setVerDropdownOpen(false); }, 250); }} className={cn("p-1.5 rounded-lg transition-colors", isLight ? "hover:bg-black/5 text-[#1a1625]/30" : "hover:bg-white/5 text-white/30")}>
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="flex-1 overflow-y-auto p-5 space-y-5 scrollbar-subtle">
              {/* 实例名称 */}
              <NewInstanceField label="名称" desc="为这个酒馆实例设置一个备注名">
                <input
                  type="text"
                  value={newInstanceName}
                  onChange={(e) => setNewInstanceName(e.target.value)}
                  placeholder="我的酒馆"
                  className={cn(
                    "w-full h-9 px-3 rounded-xl border text-sm focus:outline-none focus:ring-0 transition-colors",
                    isLight
                      ? "bg-black/[0.04] border-black/[0.08] text-[#1a1625] placeholder:text-[#1a1625]/25 focus:border-[#1a1625]/20"
                      : "bg-white/[0.04] border-white/[0.08] text-white placeholder:text-white/25 focus:border-white/20"
                  )}
                />
              </NewInstanceField>

              {/* 实例模式 */}
              <div>
                <div className={cn("text-xs font-medium mb-2", isLight ? "text-[#1a1625]/70" : "text-white/70")}>实例模式</div>
                <div className="flex gap-2">
                  <button
                    onClick={() => setNewInstanceMode("local")}
                    className={cn(
                      "flex-1 h-9 rounded-xl text-xs font-medium border transition-all",
                      newInstanceMode === "local"
                        ? isLight ? "bg-[#1a1625]/8 border-[#1a1625]/15 text-[#1a1625]" : "bg-white/10 border-white/15 text-white"
                        : isLight ? "bg-transparent border-black/[0.06] text-[#1a1625]/35 hover:border-black/12 hover:text-[#1a1625]/55" : "bg-transparent border-white/[0.06] text-white/35 hover:border-white/12 hover:text-white/55"
                    )}
                  >本地实例</button>
                  <button
                    onClick={() => setNewInstanceMode("remote")}
                    className={cn(
                      "flex-1 h-9 rounded-xl text-xs font-medium border transition-all",
                      newInstanceMode === "remote"
                        ? isLight ? "bg-[#1a1625]/8 border-[#1a1625]/15 text-[#1a1625]" : "bg-white/10 border-white/15 text-white"
                        : isLight ? "bg-transparent border-black/[0.06] text-[#1a1625]/35 hover:border-black/12 hover:text-[#1a1625]/55" : "bg-transparent border-white/[0.06] text-white/35 hover:border-white/12 hover:text-white/55"
                    )}
                  >远程连接</button>
                </div>
              </div>

              {/* 本地模式配置 */}
              {newInstanceMode === "local" && (
                <>
                  <NewInstanceField label="安装目录" desc="SillyTavern 的安装位置">
                    <div className="flex items-center gap-2 w-full">
                      <input
                        type="text"
                        value={newInstanceDir}
                        onChange={(e) => setNewInstanceDir(e.target.value)}
                        placeholder="选择或输入路径"
                        className={cn(
                          "flex-1 h-9 px-3 rounded-xl border text-sm focus:outline-none focus:ring-0 transition-colors",
                          isLight
                            ? "bg-black/[0.04] border-black/[0.08] text-[#1a1625] placeholder:text-[#1a1625]/25 focus:border-[#1a1625]/20"
                            : "bg-white/[0.04] border-white/[0.08] text-white placeholder:text-white/25 focus:border-white/20"
                        )}
                      />
                      <button onClick={async () => {
                        try {
                          const { name } = await TarvenEnv.pickDirectory();
                          setNewInstanceDir(name);
                        } catch { /* 取消 */ }
                      }} className={cn(
                        "h-9 px-3 rounded-xl text-[11px] font-medium border flex-shrink-0 transition-all",
                        isLight ? "border-black/[0.08] text-[#1a1625]/50 hover:bg-black/[0.04]" : "border-white/[0.08] text-white/50 hover:bg-white/[0.04]"
                      )}>浏览</button>
                    </div>
                  </NewInstanceField>
                  <NewInstanceField label="SillyTavern 版本" desc="从 GitHub releases 获取可用版本">
                    <button
                      id="ver-trigger"
                      onClick={async () => {
                        if (verDropdownOpen) {
                          setVerDropdownOpen(false);
                        } else {
                          // 首次打开时拉取 GitHub releases
                          if (releases.length === 0 && !fetchingReleases) {
                            setFetchingReleases(true);
                            try {
                              const { releases: r } = await TarvenEnv.fetchReleases();
                              setReleases(r);
                            } catch { /* 网络失败,保留默认选项 */ }
                            setFetchingReleases(false);
                          }
                          const trigger = document.getElementById('ver-trigger');
                          if (trigger) {
                            const r = trigger.getBoundingClientRect();
                            setVerDropdownPos({ top: r.bottom + 4, left: r.left, width: r.width });
                          }
                          setVerDropdownOpen(true);
                        }
                      }}
                      className={cn(
                        "w-full h-9 px-3 rounded-xl border text-sm text-left flex items-center justify-between transition-colors",
                        isLight
                          ? "bg-black/[0.04] border-black/[0.08] text-[#1a1625]"
                          : "bg-white/[0.04] border-white/[0.08] text-white"
                      )}
                    >
                      <span>{fetchingReleases ? "正在获取版本..." : (newInstanceVersion === "stable" ? "稳定版 (推荐)" : newInstanceVersion === "latest" ? "最新版" : newInstanceVersion)}</span>
                      <ChevronDown className="w-3.5 h-3.5 opacity-40" />
                    </button>
                  </NewInstanceField>
                </>
              )}

              {/* 远程模式配置 */}
              {newInstanceMode === "remote" && (
                <NewInstanceField label="连接地址" desc="SillyTavern 服务的 URL 地址">
                  <input
                    type="text"
                    value={newInstanceUrl}
                    onChange={(e) => setNewInstanceUrl(e.target.value)}
                    placeholder="http://192.168.1.100:8000"
                    className={cn(
                      "w-full h-9 px-3 rounded-xl border text-sm focus:outline-none focus:ring-0 transition-colors",
                      isLight
                        ? "bg-black/[0.04] border-black/[0.08] text-[#1a1625] placeholder:text-[#1a1625]/25 focus:border-[#1a1625]/20"
                        : "bg-white/[0.04] border-white/[0.08] text-white placeholder:text-white/25 focus:border-white/20"
                    )}
                  />
                </NewInstanceField>
              )}
            </div>

            {/* 底部按钮 */}
            <div className={cn("flex items-center justify-end gap-2 px-5 py-3 border-t flex-shrink-0", isLight ? "border-black/[0.06]" : "border-white/[0.06]")}>
              <button onClick={() => { setIsNewInstancePanelClosing(true); setTimeout(() => { setShowNewInstancePanel(false); setIsNewInstancePanelClosing(false); }, 250); }} className={cn(
                "px-4 h-8 rounded-xl text-[11px] font-medium transition-all active:scale-[0.97]",
                isLight ? "bg-black/[0.05] text-[#1a1625]/45 hover:bg-black/[0.08]" : "bg-white/[0.06] text-white/45 hover:bg-white/10"
              )}>取消</button>
              <button
                onClick={() => {
                  const name = newInstanceName.trim() || "新实例";
                  // 选中 release 时取其 tag/zipballUrl;否则用默认 stable(走预打包)
                  const selRelease = releases.find(r => r.tag === newInstanceVersion);
                  const newInstance: TavernInstance = {
                    id: `new-${Date.now()}`,
                    name: "SillyTavern",
                    subtitle: name,
                    version: newInstanceVersion === "stable" ? "stable" : newInstanceVersion,
                    type: newInstanceMode,
                    status: newInstanceMode === "local" ? "stopped" : "offline",
                    icon: newInstanceMode === "local" ? <Folder className="w-5 h-5" /> : <Cloud className="w-5 h-5" />,
                    color: "#6366f1",
                    createdAt: new Date().toISOString().slice(0, 10),
                    lastUsed: "—",
                    totalUsage: "0h",
                    ...(newInstanceMode === "local"
                      ? { port: 8000 + instances.filter(t => t.type === "local").length, installDir: newInstanceDir || `local-${Date.now()}`, zipballUrl: selRelease?.zipballUrl, config: { ...DEFAULT_CONFIG } }
                      : { url: newInstanceUrl }),
                  };
                  setInstances(prev => [...prev, newInstance]);
                  setIsNewInstancePanelClosing(true);
                  setTimeout(() => { setShowNewInstancePanel(false); setIsNewInstancePanelClosing(false); setNewInstanceName(""); setNewInstanceDir(""); setNewInstanceUrl("http://"); setNewInstanceVersion("stable"); setNewInstanceZipUrl(undefined); }, 250);
                }}
                className={cn(
                  "px-4 h-8 rounded-xl text-[11px] font-semibold transition-all active:scale-[0.97]",
                  isLight ? "bg-[#1a1625] text-[#f5f3ef] hover:bg-[#1a1625]/90" : "bg-white/90 text-[#1a1625] hover:bg-white"
                )}
              >创建</button>
            </div>
          </div>
        </>
      )}

      {/* 版本下拉菜单 — 渲染在面板外部避免 transform 裁剪 */}
      {verDropdownOpen && (
        <>
          <div className={cn(
            "fixed inset-0 z-[70] overlay-backdrop",
            verDropdownClosing && "overlay-backdrop-exit"
          )} onClick={() => setVerDropdownOpen(false)} />
          <div className={cn(
            "fixed z-[72] rounded-md border overflow-hidden shadow-2xl animate-dropdown",
            isLight ? "bg-[#f5f3ef]/95 border-black/[0.08]" : "bg-[#1a1625]/92 border-white/[0.08]"
          )} style={{
            top: verDropdownPos.top,
            left: verDropdownPos.left,
            width: verDropdownPos.width,
          }}>
            {[
              { value: "stable", label: "稳定版", sublabel: "预打包, 无需额外下载", zipballUrl: undefined },
              ...releases.slice(0, 20).map(r => ({
                value: r.tag,
                label: r.tag,
                sublabel: r.prerelease ? "预发布版本" : "正式版本",
                zipballUrl: r.zipballUrl,
                recommended: r.tag === releases.find(x => !x.prerelease)?.tag
              })),
            ].map(opt => (
              <button
                key={opt.value}
                onClick={() => {
                  setNewInstanceVersion(opt.value);
                  setNewInstanceZipUrl(opt.zipballUrl);
                  setVerDropdownOpen(false);
                }}
                className={cn(
                  "w-full px-4 py-2.5 text-left transition-colors flex items-center justify-between gap-2",
                  newInstanceVersion === opt.value
                    ? isLight ? "bg-[#1a1625]/8" : "bg-white/10"
                    : isLight ? "hover:bg-black/[0.04]" : "hover:bg-white/[0.06]"
                )}
              >
                <div className="flex flex-col items-start min-w-0">
                  <span className={cn("text-[13px] font-medium", newInstanceVersion === opt.value
                    ? isLight ? "text-[#1a1625]" : "text-white"
                    : isLight ? "text-[#1a1625]/80" : "text-white/80"
                  )}>
                    {opt.label}
                    {(opt as any).recommended && <span className={cn("ml-1 text-[10px] px-1.5 py-0.5 rounded-full", isLight ? "bg-[#1a1625]/10 text-[#1a1625]/60" : "bg-white/10 text-white/60")}>推荐</span>}
                  </span>
                  <span className={cn("text-[11px] mt-0.5", isLight ? "text-[#1a1625]/40" : "text-white/40")}>{opt.sublabel}</span>
                </div>
                {newInstanceVersion === opt.value && <Check className={cn("w-4 h-4 flex-shrink-0", isLight ? "text-[#1a1625]/60" : "text-white/60")} />}
              </button>
            ))}
          </div>
        </>
      )}

      {/* 管理面板 */}
      {(() => {
        const mp = showManagePanel;
        if (!mp && !isManagePanelClosing) return null;
        return (
        <>
          <div className={cn(
            "fixed inset-0 z-[60] bg-black/40 backdrop-blur-sm overlay-backdrop",
            isManagePanelClosing && "overlay-backdrop-exit"
          )} onClick={() => { setShowManagePanel(null); setIsManagePanelClosing(false); setManageTab("general"); }} />
          <div className={cn(
            "fixed z-[62] rounded-2xl border backdrop-blur-[40px] saturate-180 flex flex-col overflow-hidden",
            isManagePanelClosing ? "animate-clone-panel-exit" : "animate-clone-panel",
            isLight
              ? "bg-[#f5f3ef]/95 border-black/[0.08] shadow-[0_16px_60px_rgba(0,0,0,0.12)]"
              : "bg-[#1a1625]/90 border-white/[0.08] shadow-[0_16px_60px_rgba(0,0,0,0.4)]"
          )} style={{
            top: '50%',
            left: '50%',
            transform: 'translate(-50%, -50%)',
            width: 'min(480px, calc(100vw - 2rem))',
            maxHeight: 'min(80vh, calc(100vh - 2rem))',
          }}>
            {/* 头部 */}
            <div className={cn("flex items-center justify-between px-5 h-12 flex-shrink-0 border-b", isLight ? "border-black/[0.06]" : "border-white/[0.06]")}>
              <div className="flex items-center gap-2">
                <span className={cn("text-sm font-semibold", isLight ? "text-[#1a1625]" : "text-white")}>{mp!.subtitle || mp!.name}</span>
                <span className={cn("text-[10px] px-1.5 py-0.5 rounded-md", isLight ? "bg-black/[0.05] text-[#1a1625]/35" : "bg-white/[0.06] text-white/35")}>{mp!.version || "—"}</span>
              </div>
              <button onClick={() => { setIsManagePanelClosing(true); setTimeout(() => { setShowManagePanel(null); setIsManagePanelClosing(false); setManageTab("general"); }, 250); }} className={cn("p-1.5 rounded-lg transition-colors", isLight ? "hover:bg-black/5 text-[#1a1625]/30" : "hover:bg-white/5 text-white/30")}>
                <X className="w-4 h-4" />
              </button>
            </div>

            {/* Tab 导航 */}
            <div className={cn("flex items-center gap-1 px-5 pt-3 pb-2 flex-shrink-0", isLight ? "border-b border-black/[0.06]" : "border-b border-white/[0.06]")}>
              {([
                { id: "general", label: "通用" },
                { id: "about", label: "关于" },
              ] as const).map(tab => (
                <button
                  key={tab.id}
                  onClick={() => setManageTab(tab.id)}
                  className={cn(
                    "px-3 py-1.5 rounded-lg text-[11px] font-medium transition-all",
                    manageTab === tab.id
                      ? isLight ? "bg-[#1a1625]/8 text-[#1a1625]" : "bg-white/10 text-white"
                      : isLight ? "text-[#1a1625]/35 hover:text-[#1a1625]/55" : "text-white/35 hover:text-white/55"
                  )}
                >{tab.label}</button>
              ))}
            </div>

            {/* Tab 内容 */}
            <div className="flex-1 overflow-y-auto p-5 space-y-4 scrollbar-subtle">
              {manageTab === "general" && (
                <>
                  {(() => {
                    const cfg = showManagePanel!.config ?? DEFAULT_CONFIG;
                    return (
                    <>
                  <ManageItem label="启动端口" desc="宿主 WebView 和本地服务都会使用这个端口">
                    <input type="number" value={showManagePanel?.port ?? 8000} onChange={(e) => {
                      const port = parseInt(e.target.value) || 8000;
                      if (showManagePanel) setInstances(prev => prev.map(t => t.id === showManagePanel.id ? { ...t, port } : t));
                    }} className={cn("w-20 h-7 px-2 rounded-lg text-xs text-center border focus:outline-none focus:ring-0 transition-colors",
                      isLight ? "bg-black/[0.04] border-black/[0.08] text-[#1a1625] focus:border-[#1a1625]/20" : "bg-white/[0.04] border-white/[0.08] text-white focus:border-white/20"
                    )} />
                  </ManageItem>
                  <ManageItem label="允许外部监听" desc="Android 宿主默认建议关闭，只在明确需要局域网访问时开启">
                    <ToggleSwitch on={cfg.listen} onChange={(v) => updateManagedConfig({ listen: v })} />
                  </ManageItem>
                  <ManageItem label="启用 IPv4" desc="至少要保留一个网络协议可用">
                    <ToggleSwitch on={cfg.ipv4} onChange={(v) => updateManagedConfig({ ipv4: v })} />
                  </ManageItem>
                  <ManageItem label="启用 IPv6" desc="如果网络环境稳定支持 IPv6，可以开启">
                    <ToggleSwitch on={cfg.ipv6} onChange={(v) => updateManagedConfig({ ipv6: v })} />
                  </ManageItem>
                  <ManageItem label="优先使用 IPv6 DNS" desc="在 IPv6 网络质量足够好时再开启">
                    <ToggleSwitch on={cfg.dnsIpv6} onChange={(v) => updateManagedConfig({ dnsIpv6: v })} />
                  </ManageItem>
                  <ManageItem label="心跳写入间隔" desc="单位秒，填 0 关闭心跳文件">
                    <input type="number" value={cfg.heartbeat} onChange={(e) => {
                      const heartbeat = parseInt(e.target.value) || 0;
                      updateManagedConfig({ heartbeat });
                    }} className={cn("w-20 h-7 px-2 rounded-lg text-xs text-center border focus:outline-none focus:ring-0 transition-colors",
                      isLight ? "bg-black/[0.04] border-black/[0.08] text-[#1a1625] focus:border-[#1a1625]/20" : "bg-white/[0.04] border-white/[0.08] text-white focus:border-white/20"
                    )} />
                  </ManageItem>
                  <ManageItem label="启用 HTTP Keep-Alive" desc="网络波动大时可临时关闭">
                    <ToggleSwitch on={cfg.keepAlive} onChange={(v) => updateManagedConfig({ keepAlive: v })} />
                  </ManageItem>
                    </>
                    );
                  })()}
                </>
              )}
              {manageTab === "about" && (
                <div className="space-y-3">
                  <div className={cn("flex items-center justify-between py-2 border-b", isLight ? "border-black/[0.04]" : "border-white/[0.04]")}>
                    <span className={cn("text-xs", isLight ? "text-[#1a1625]/40" : "text-white/40")}>实例名称</span>
                    <span className={cn("text-xs font-medium", isLight ? "text-[#1a1625]/70" : "text-white/70")}>{showManagePanel!.subtitle || showManagePanel!.name}</span>
                  </div>
                  <div className={cn("flex items-center justify-between py-2 border-b", isLight ? "border-black/[0.04]" : "border-white/[0.04]")}>
                    <span className={cn("text-xs", isLight ? "text-[#1a1625]/40" : "text-white/40")}>版本</span>
                    <span className={cn("text-xs font-medium", isLight ? "text-[#1a1625]/70" : "text-white/70")}>
                      {showManagePanel!.type === "local" ? (aboutInfo?.version && aboutInfo.version !== "unknown" ? `v${aboutInfo.version}` : showManagePanel!.version || "—") : showManagePanel!.version || "—"}
                    </span>
                  </div>
                  <div className={cn("flex items-center justify-between py-2 border-b", isLight ? "border-black/[0.04]" : "border-white/[0.04]")}>
                    <span className={cn("text-xs", isLight ? "text-[#1a1625]/40" : "text-white/40")}>类型</span>
                    <span className={cn("text-xs font-medium", isLight ? "text-[#1a1625]/70" : "text-white/70")}>{showManagePanel!.type === "local" ? "本地实例" : "远程实例"}</span>
                  </div>
                  <div className={cn("flex items-center justify-between py-2 border-b", isLight ? "border-black/[0.04]" : "border-white/[0.04]")}>
                    <span className={cn("text-xs", isLight ? "text-[#1a1625]/40" : "text-white/40")}>状态</span>
                    <span className={cn("text-xs font-medium", showManagePanel!.type === "local" ? "text-sky-400/80" : showManagePanel!.status === "online" ? "text-emerald-400/80" : "text-red-400/70")}>
                      {showManagePanel!.type === "local" ? (aboutInfo?.status || "本地") : getStatusText(showManagePanel!.status)}
                    </span>
                  </div>
                  <div className={cn("flex items-center justify-between py-2 border-b", isLight ? "border-black/[0.04]" : "border-white/[0.04]")}>
                    <span className={cn("text-xs", isLight ? "text-[#1a1625]/40" : "text-white/40")}>创建时间</span>
                    <span className={cn("text-xs font-medium tabular-nums", isLight ? "text-[#1a1625]/70" : "text-white/70")}>
                      {showManagePanel!.type === "local" && aboutInfo?.createdAt ? aboutInfo.createdAt : (showManagePanel!.createdAt || "—")}
                    </span>
                  </div>
                  <div className={cn("flex items-center justify-between py-2 border-b", isLight ? "border-black/[0.04]" : "border-white/[0.04]")}>
                    <span className={cn("text-xs", isLight ? "text-[#1a1625]/40" : "text-white/40")}>占用空间</span>
                    <span className={cn("text-xs font-medium tabular-nums", isLight ? "text-[#1a1625]/70" : "text-white/70")}>
                      {showManagePanel!.type === "local" && aboutInfo?.sizeBytes ? `${(aboutInfo.sizeBytes / 1024 / 1024).toFixed(1)} MB` : (showManagePanel!.totalUsage || "—")}
                    </span>
                  </div>
                  {showManagePanel!.type === "local" && aboutInfo?.path && (
                    <div className={cn("py-2")}>
                      <span className={cn("text-xs block mb-1", isLight ? "text-[#1a1625]/40" : "text-white/40")}>安装路径</span>
                      <span className={cn("text-[10px] font-mono break-all", isLight ? "text-[#1a1625]/50" : "text-white/50")}>{aboutInfo.path}</span>
                    </div>
                  )}
                  {showManagePanel!.type === "remote" && (
                    <div className={cn("py-2")}>
                      <span className={cn("text-xs block mb-1", isLight ? "text-[#1a1625]/40" : "text-white/40")}>连接地址</span>
                      <span className={cn("text-[10px] font-mono break-all", isLight ? "text-[#1a1625]/50" : "text-white/50")}>{showManagePanel!.url || "—"}</span>
                    </div>
                  )}
                </div>
              )}
            </div>

            {/* 底部按钮 */}
            <div className={cn("flex items-center justify-end gap-2 px-5 py-3 border-t flex-shrink-0", isLight ? "border-black/[0.06]" : "border-white/[0.06]")}>
              <button onClick={() => { setIsManagePanelClosing(true); setTimeout(() => { setShowManagePanel(null); setIsManagePanelClosing(false); setManageTab("general"); }, 250); }} className={cn(
                "px-4 h-8 rounded-xl text-[11px] font-medium transition-all active:scale-[0.97]",
                isLight ? "bg-black/[0.05] text-[#1a1625]/45 hover:bg-black/[0.08]" : "bg-white/[0.06] text-white/45 hover:bg-white/10"
              )}>取消</button>
              <button className={cn(
                "px-4 h-8 rounded-xl text-[11px] font-semibold transition-all active:scale-[0.97]",
                isLight ? "bg-[#1a1625] text-[#f5f3ef] hover:bg-[#1a1625]/90" : "bg-white/90 text-[#1a1625] hover:bg-white"
              )}>保存</button>
            </div>
          </div>
        </>
        );
      })()}

    </div>
  );
}

export default SillyClientLauncher;
