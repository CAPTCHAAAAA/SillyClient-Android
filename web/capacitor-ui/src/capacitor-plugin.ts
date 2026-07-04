import { registerPlugin, PluginListenerHandle } from '@capacitor/core'

/**
 * TarvenEnv 插件 —— 由原生侧 com.sillyclient.plugin.TarvenEnvPlugin 实现。
 *
 * 职责:provision/启动本地 Node 实例、进入/退出沉浸式 WebView(承酒馆,本地或远程)。
 * 端口与目标 URL 由前端实例数据决定,不再硬编码 8000。
 */

/** 本地实例的 SillyTavern 运行配置(映射管理面板全部设置项)。 */
export interface InstanceConfig {
  listen: boolean
  ipv4: boolean
  ipv6: boolean
  dnsIpv6: boolean
  heartbeat: number
  keepAlive: boolean
}

export const DEFAULT_CONFIG: InstanceConfig = {
  listen: false,
  ipv4: true,
  ipv6: false,
  dnsIpv6: false,
  heartbeat: 0,
  keepAlive: false,
}

/** GitHub release 条目(来自 SillyTavern/SillyTavern releases)。 */
export interface GithubRelease {
  tag: string
  name: string
  publishedAt: string
  zipballUrl: string
  prerelease: boolean
}

/** 自检发现的本地实例。 */
export interface ScannedInstance {
  instanceId: string
  version: string
  path: string
  sizeBytes: number
  hasServer: boolean
}

/** 实例详情(管理面板「关于」真实数据)。 */
export interface InstanceInfo {
  instanceId: string
  version: string
  path: string
  sizeBytes: number
  createdAt: string
  port: number
  status: string
}

export interface TarvenEnvPlugin {
  provisionAndStart(options: {
    port: number
    instanceId: string
    version: string
    zipballUrl?: string
    config: InstanceConfig
  }): Promise<{ ready: boolean }>

  enterImmersive(options: { url: string }): Promise<void>
  exitImmersive(): Promise<void>
  getStatus(): Promise<{ serverReady: boolean; mode: string; url?: string }>

  /** 拉取 GitHub SillyTavern releases 列表。 */
  fetchReleases(): Promise<{ releases: GithubRelease[] }>

  /** 调用系统目录选择器,返回选中的目录显示名(用作实例安装标识)。 */
  pickDirectory(): Promise<{ name: string; path: string }>

  /** 调用系统图片选择器,把图片复制到 covers/{instanceId},返回可加载的文件路径。 */
  pickImage(options: { instanceId: string }): Promise<{ path: string }>

  /** 自检:扫描本地已存在的酒馆实例目录。 */
  scanInstances(): Promise<{ instances: ScannedInstance[] }>

  /** 读取实例详情(关于页真实数据)。 */
  getInstanceInfo(options: { instanceId: string; port?: number }): Promise<InstanceInfo>

  /** 向运行中的 Node 进程 stdin 发送命令(termux 式终端输入)。 */
  sendCommand(options: { text: string }): Promise<void>

  /** 刷新酒馆 WebView。 */
  reloadTavern(): Promise<void>

  /** 清空宿主 WebView 缓存/Cookie/历史。 */
  clearWebViewData(): Promise<void>

  /** 获取安全 insets(挖孔/状态栏避让)。 */
  getSafeInsets(): Promise<{ top: number; bottom: number; left: number; right: number }>

  /** 启用/禁用酒馆 WebView 下拉刷新。 */
  setPullToRefresh(options: { enabled: boolean }): Promise<void>

  addListener(
    eventName: 'log' | 'progress' | 'ready' | 'mode',
    listenerFunc: (data: any) => void,
  ): Promise<PluginListenerHandle>
}

export const TarvenEnv = registerPlugin<TarvenEnvPlugin>('TarvenEnv')
