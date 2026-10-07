import type { InstallPathMode } from "../capacitor-plugin";

export function cleanInstallPath(value: string): string | undefined {
  return value.trim().replace(/^["']|["']$/g, "").trim() || undefined;
}

function executablePath(value: string): string | undefined {
  const path = cleanInstallPath(value);
  if (path && /^[a-z][a-z0-9+.-]*:\/\//i.test(path)) {
    throw new Error("安装目录必须是可执行的本地路径，文档提供器位置只能用于导入");
  }
  return path ? rootTypedPath(path) : undefined;
}

/**
 * Typed or pasted paths ("Download/SillyClient", "内部存储/下载") are rooted at
 * shared storage; the native side requires absolute paths.
 */
function rootTypedPath(path: string): string {
  if (path.startsWith("/") || /^[a-z]:[\\/]/i.test(path)) return path;
  const stripped = path.replace(/^(?:内部存储|手机存储|内部共享存储空间)[\\/]+/, "");
  return `/storage/emulated/0/${stripped}`;
}

export function installationSelection(selection: { path?: string; installPathMode?: InstallPathMode }) {
  const path = executablePath(selection.path || "");
  if (!path) throw new Error("目录选择器未返回实际路径");
  const mode = selection.installPathMode ?? "exact";
  if (mode !== "root" && mode !== "exact") throw new Error("目录选择器返回了无效的路径模式");
  return { path, mode };
}

/**
 * 向导里的"安装目录 / 目标保存位置"一律解释为"实例存放文件夹"（父目录）：
 * 去引号并把相对写法补全为绝对路径（Download/… → /storage/emulated/0/Download/…）。
 * 实例永远是该文件夹的一级子目录 —— 手动输入与「浏览」选择同一条语义。
 */
export function instanceParentPath(value: string): string | undefined {
  return executablePath(value);
}

/** Resolve an explicit parent selection or an exact path into the native execution target. */
export function exactInstallTarget(value: string, mode: InstallPathMode, instanceIdOrName: string): string | undefined {
  const path = executablePath(value);
  if (!path) return undefined;
  if (mode === "exact") return path;
  return buildInstanceSubfolder(path, instanceIdOrName);
}

export function sanitizeFolderName(name: string): string {
  return name
    .trim()
    .replace(/[\\/:*?"<>|\x00-\x1f]/g, "-")
    .replace(/^[. ]+|[. ]+$/g, "")
    .slice(0, 100) || "instance";
}

/**
 * Builds a dedicated subfolder for the instance under the parent directory.
 * If the path already ends with the instance name, avoids duplicating.
 */
export function buildInstanceSubfolder(parentDir: string, instanceName: string): string {
  const clean = parentDir.trim().replace(/^["']|["']$/g, "").trim().replace(/[\\/]+$/, "");
  const separator = clean.includes("\\") || /^[a-z]:/i.test(clean) ? "\\" : "/";
  const safeName = sanitizeFolderName(instanceName);
  const currentBase = clean.slice(clean.lastIndexOf(separator) + 1);
  if (currentBase.toLowerCase() === safeName.toLowerCase()) {
    return clean;
  }
  return `${clean}${separator}${safeName}`;
}
