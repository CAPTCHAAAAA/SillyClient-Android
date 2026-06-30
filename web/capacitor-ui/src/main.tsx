import React, { useState, useEffect } from 'react';
import ReactDOM from 'react-dom/client';
import { TarvenEnv } from './capacitor-plugin';

function App() {
  const [log, setLog] = useState<string[]>([]);
  const [ready, setReady] = useState(false);
  const [pct, setPct] = useState(0);
  const [mode, setMode] = useState('dashboard');

  const addLog = (msg: string) => setLog(prev => [...prev.slice(-49), msg]);

  // ── Listen to plugin events ──
  useEffect(() => {
    const onLog = (d: { line: string }) => addLog(d.line);
    const onProgress = (d: { pct: number; text?: string }) => {
      setPct(d.pct);
      if (d.text) addLog(d.text);
    };
    const onReady = (d: { ready: boolean }) => setReady(d.ready);
    const onMode = (d: { mode: string }) => setMode(d.mode);

    // ponytail: Capacitor addListener returns a handle with remove()
    const h1 = TarvenEnv.addListener('log', onLog);
    const h2 = TarvenEnv.addListener('progress', onProgress);
    const h3 = TarvenEnv.addListener('ready', onReady);
    const h4 = TarvenEnv.addListener('mode', onMode);

    return () => { h1.remove(); h2.remove(); h3.remove(); h4.remove(); };
  }, []);

  // ── Actions ──
  const handleProvision = async () => {
    try {
      addLog('→ Provisioning...');
      await TarvenEnv.provisionAndStart();
      addLog('✓ Done');
    } catch (e) { addLog('✗ ' + String(e)); }
  };

  const handleEnter = async () => {
    try {
      await TarvenEnv.enterImmersive();
    } catch (e) { addLog('✗ ' + String(e)); }
  };

  const handleExit = async () => {
    try {
      await TarvenEnv.exitImmersive();
    } catch (e) { addLog('✗ ' + String(e)); }
  };

  const handleStatus = async () => {
    try {
      const s = await TarvenEnv.getStatus();
      addLog(s.ready ? 'Server: ready' : 'Server: not ready');
    } catch (e) { addLog('✗ ' + String(e)); }
  };

  const inTavern = mode === 'tavern';

  const btn: React.CSSProperties = {
    padding: '14px 28px', fontSize: 16, borderRadius: 12,
    border: '1px solid #333', background: '#1a1a2e', color: '#e0e0e0',
    cursor: 'pointer', width: 220, textAlign: 'center' as const,
  };

  return (
    <div style={{
      display: 'flex', flexDirection: 'column', alignItems: 'center',
      justifyContent: 'center', minHeight: '100%', gap: 12, padding: 32,
    }}>
      <h1 style={{ fontSize: 28, fontWeight: 700, letterSpacing: 1 }}>SillyClient</h1>
      <p style={{ color: '#666', fontSize: 13, marginBottom: 4 }}>
        Mode: {mode} {ready ? '· Server Ready' : ''}
      </p>

      {/* Progress bar */}
      <div style={{ width: 240, height: 4, background: '#1a1a2e', borderRadius: 2, marginBottom: 8 }}>
        <div style={{ width: `${pct}%`, height: '100%', background: ready ? '#4a4' : '#48f', borderRadius: 2, transition: 'width 0.3s' }} />
      </div>

      {!inTavern && (
        <>
          <button onClick={handleProvision} style={btn}>Provision & Start</button>
          <button onClick={handleEnter} style={{
            ...btn, background: ready ? '#1a3a1a' : '#2a2a2a',
            borderColor: ready ? '#3a3' : '#333',
          }}>Enter Tavern</button>
        </>
      )}
      {inTavern && (
        <button onClick={handleExit} style={{...btn, background: '#3a1a1a', borderColor: '#a33'}}>
          Exit Tavern
        </button>
      )}
      <button onClick={handleStatus} style={{...btn, width: 160}}>Status</button>

      {/* Log panel */}
      <div style={{
        marginTop: 12, width: '100%', maxWidth: 380, maxHeight: 220,
        overflowY: 'auto', background: '#0d0d1a', borderRadius: 8,
        padding: 12, fontSize: 11, fontFamily: 'monospace', color: '#888',
      }}>
        {log.map((l, i) => <div key={i}>{l}</div>)}
      </div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode><App /></React.StrictMode>
);