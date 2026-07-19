# SillyClient UI

Android、Windows 和 GitHub Pages 共用的 React 控制台。这里是唯一源码位置。

```bash
pnpm install --frozen-lockfile
pnpm run dev
pnpm run typecheck
pnpm run build
```

`pnpm run build:android` 会构建并把 `dist/` 同步到 Android 的 `app/src/main/assets/public/`。Windows 和 Pages 使用各自仓库的同步步骤，不直接修改生成副本。

平台能力通过 `src/capacitor-plugin.ts` 中的 `TarvenEnv` 契约调用。浏览器预览使用 shim；新增或修改方法时必须同时更新 Kotlin 与 Electron 实现。
