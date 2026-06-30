import { registerPlugin } from '@capacitor/core';
import type { PluginListenerHandle } from '@capacitor/core';

export interface TarvenEnvPlugin {
  provisionAndStart(): Promise<void>;
  enterImmersive(): Promise<void>;
  exitImmersive(): Promise<void>;
  getStatus(): Promise<{ ready: boolean }>;
  // Events pushed via notifyListeners from native side
  addListener(event: 'log',    cb: (d: { line: string }) => void): Promise<PluginListenerHandle>;
  addListener(event: 'progress', cb: (d: { pct: number; text?: string }) => void): Promise<PluginListenerHandle>;
  addListener(event: 'ready',  cb: (d: { ready: boolean }) => void): Promise<PluginListenerHandle>;
  addListener(event: 'mode',   cb: (d: { mode: string }) => void): Promise<PluginListenerHandle>;
}

export const TarvenEnv = registerPlugin<TarvenEnvPlugin>('TarvenEnv');