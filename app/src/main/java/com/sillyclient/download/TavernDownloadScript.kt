package com.sillyclient.download

/** JavaScript installed into an allowed top-level Tavern page to retain and stream exports. */
object TavernDownloadScript {
    fun build(capabilityToken: String): String {
        require(capabilityToken.matches(Regex("^[A-Za-z0-9-]{16,80}$")))
        return """
            (() => {
              const bridge = window.SillyClientAndroidDownloads;
              if (!bridge || !window.URL || !window.HTMLAnchorElement) return false;

              const markerName = '__sillyClientAndroidDownloadsV1';
              const token = '$capabilityToken';
              const previous = window[markerName];
              if (previous && previous.token === token) return true;
              if (previous && typeof previous.dispose === 'function') previous.dispose();

              const retainedBlobs = new Map();
              const sources = new Map();
              const rawChunkSize = 256 * 1024;
              const retainedBlobLifetimeMs = 2 * 60 * 1000;
              const revokedBlobLifetimeMs = 60 * 1000;
              const sourceFetchTimeoutMs = 2 * 60 * 1000;
              const maxRetainedBlobCount = 32;
              const maxRetainedBlobBytes = 256 * 1024 * 1024;
              const originalCreateObjectURL = URL.createObjectURL;
              const originalRevokeObjectURL = URL.revokeObjectURL;
              const originalAnchorClick = HTMLAnchorElement.prototype.click;
              let retainedBlobBytes = 0;
              let disposed = false;

              function report(message) {
                try { bridge.reportDownloadError(token, String(message || 'Unable to export file')); }
                catch (_) { /* Native bridge is no longer available. */ }
              }

              function createTransferId() {
                const random = globalThis.crypto && typeof globalThis.crypto.randomUUID === 'function'
                  ? globalThis.crypto.randomUUID().replace(/-/g, '')
                  : Math.random().toString(36).slice(2) + Date.now().toString(36);
                return 'sc_' + random;
              }

              function releaseRetainedBlob(url) {
                const key = String(url);
                const entry = retainedBlobs.get(key);
                if (!entry) return null;
                retainedBlobs.delete(key);
                retainedBlobBytes = Math.max(0, retainedBlobBytes - entry.blob.size);
                if (entry.timer !== null) clearTimeout(entry.timer);
                return entry.blob;
              }

              function scheduleRetainedBlobRelease(url, delay) {
                const key = String(url);
                const entry = retainedBlobs.get(key);
                if (!entry) return;
                if (entry.timer !== null) clearTimeout(entry.timer);
                entry.timer = setTimeout(() => releaseRetainedBlob(key), delay);
              }

              function trimRetainedBlobs() {
                while (
                  retainedBlobs.size > maxRetainedBlobCount ||
                  (retainedBlobs.size > 1 && retainedBlobBytes > maxRetainedBlobBytes)
                ) {
                  const oldest = retainedBlobs.keys().next().value;
                  if (oldest === undefined) break;
                  releaseRetainedBlob(oldest);
                }
              }

              function rememberBlob(url, blob) {
                const key = String(url);
                releaseRetainedBlob(key);
                retainedBlobs.set(key, { blob, timer: null });
                retainedBlobBytes += blob.size;
                scheduleRetainedBlobRelease(key, retainedBlobLifetimeMs);
                trimRetainedBlobs();
              }

              function mimeFromDataUrl(url) {
                const match = /^data:([^;,]+)/i.exec(url);
                return match ? match[1] : '';
              }

              function nameFromUrl(url) {
                try {
                  const parsed = new URL(url, document.baseURI);
                  const part = parsed.pathname.split('/').filter(Boolean).pop();
                  return part ? decodeURIComponent(part) : '';
                } catch (_) {
                  return '';
                }
              }

              function beginSource(source, fileName, mimeType, expectedBytes) {
                if (disposed) return false;
                const id = createTransferId();
                sources.set(id, source);
                let accepted = false;
                try {
                  accepted = bridge.requestDownload(
                    token,
                    id,
                    String(fileName || ''),
                    String(mimeType || ''),
                    String(Number.isFinite(expectedBytes) ? expectedBytes : -1)
                  );
                } catch (error) {
                  sources.delete(id);
                  return false;
                }
                if (!accepted) {
                  sources.delete(id);
                  report('Another file export is already in progress');
                }
                // The Android host owns this download even when it rejects a concurrent request.
                return true;
              }

              function handleAnchor(anchor) {
                if (!anchor || !anchor.hasAttribute('download')) return false;
                const href = anchor.href || anchor.getAttribute('href') || '';
                if (!/^(blob:|data:|https?:)/i.test(href)) return false;
                const retained = releaseRetainedBlob(href);
                const fileName = anchor.download || nameFromUrl(href);
                if (retained) {
                  return beginSource(
                    { kind: 'blob', blob: retained },
                    fileName,
                    retained.type || anchor.type,
                    retained.size
                  );
                }
                return beginSource(
                  { kind: 'url', url: href },
                  fileName,
                  anchor.type || mimeFromDataUrl(href),
                  -1
                );
              }

              const wrappedCreateObjectURL = function(value) {
                const url = originalCreateObjectURL.call(URL, value);
                if (value instanceof Blob) rememberBlob(url, value);
                return url;
              };

              const wrappedRevokeObjectURL = function(url) {
                originalRevokeObjectURL.call(URL, url);
                // Keep only the Blob object briefly for DownloadListener fallbacks.
                scheduleRetainedBlobRelease(url, revokedBlobLifetimeMs);
              };

              const wrappedAnchorClick = function(...args) {
                if (this.isConnected || !this.hasAttribute('download')) {
                  return originalAnchorClick.apply(this, args);
                }
                const anchor = this;
                const interceptDetachedClick = function(event) {
                  if (handleAnchor(anchor)) event.preventDefault();
                };
                anchor.addEventListener('click', interceptDetachedClick, { capture: true, once: true });
                try {
                  return originalAnchorClick.apply(anchor, args);
                } finally {
                  anchor.removeEventListener('click', interceptDetachedClick, true);
                }
              };

              function captureDownloadClick(event) {
                const target = event.target;
                const anchor = target && typeof target.closest === 'function'
                  ? target.closest('a[download]')
                  : null;
                if (!handleAnchor(anchor)) return;
                event.preventDefault();
              }

              function bytesToBase64(bytes) {
                let binary = '';
                const stride = 0x8000;
                for (let offset = 0; offset < bytes.length; offset += stride) {
                  binary += String.fromCharCode.apply(null, bytes.subarray(offset, offset + stride));
                }
                return btoa(binary);
              }

              function sendBytes(id, progress, bytes) {
                for (let offset = 0; offset < bytes.length; offset += rawChunkSize) {
                  const part = bytes.subarray(offset, Math.min(offset + rawChunkSize, bytes.length));
                  const accepted = bridge.appendDownloadChunk(
                    token,
                    id,
                    progress.sequence,
                    bytesToBase64(part)
                  );
                  if (!accepted) throw new Error('Android rejected an export chunk');
                  progress.sequence += 1;
                  progress.bytes += part.byteLength;
                }
              }

              async function streamBlob(id, progress, blob) {
                for (let offset = 0; offset < blob.size; offset += rawChunkSize) {
                  const part = blob.slice(offset, offset + rawChunkSize);
                  const buffer = typeof part.arrayBuffer === 'function'
                    ? await part.arrayBuffer()
                    : await new Promise((resolve, reject) => {
                        const reader = new FileReader();
                        reader.onload = () => resolve(reader.result);
                        reader.onerror = () => reject(reader.error || new Error('Unable to read export Blob'));
                        reader.readAsArrayBuffer(part);
                      });
                  sendBytes(id, progress, new Uint8Array(buffer));
                }
              }

              async function streamResponse(id, progress, response) {
                if (response.body && typeof response.body.getReader === 'function') {
                  const reader = response.body.getReader();
                  while (true) {
                    const result = await reader.read();
                    if (result.done) break;
                    if (result.value) sendBytes(id, progress, result.value);
                  }
                  return;
                }
                await streamBlob(id, progress, await response.blob());
              }

              const startDownload = async function(id) {
                const source = sources.get(id);
                if (!source || source.started) return false;
                source.started = true;
                const progress = { sequence: 0, bytes: 0 };
                let sourceTimeout = null;
                let abortController = null;
                try {
                  if (source.kind === 'blob') {
                    await streamBlob(id, progress, source.blob);
                  } else {
                    abortController = typeof AbortController === 'function' ? new AbortController() : null;
                    if (abortController) {
                      sourceTimeout = setTimeout(() => abortController.abort(), sourceFetchTimeoutMs);
                    }
                    const response = await fetch(source.url, {
                      credentials: 'include',
                      cache: 'no-store',
                      signal: abortController ? abortController.signal : undefined
                    });
                    if (!response.ok) throw new Error('Download failed with HTTP ' + response.status);
                    await streamResponse(id, progress, response);
                  }
                  const finished = bridge.finishDownload(
                    token,
                    id,
                    progress.sequence,
                    String(progress.bytes)
                  );
                  if (!finished) throw new Error('Android could not finish the export');
                  sources.delete(id);
                  return true;
                } catch (error) {
                  sources.delete(id);
                  try {
                    bridge.abortDownload(token, id, String(error && error.message ? error.message : error));
                  } catch (_) { /* Native side already closed the transfer. */ }
                  return false;
                } finally {
                  if (sourceTimeout !== null) clearTimeout(sourceTimeout);
                }
              };

              const requestUrlDownload = function(url, fileName, mimeType, expectedBytes) {
                const value = String(url || '');
                if (!/^(blob:|data:|https?:)/i.test(value)) return false;
                const retained = releaseRetainedBlob(value);
                if (retained) {
                  return beginSource(
                    { kind: 'blob', blob: retained },
                    fileName,
                    retained.type || mimeType,
                    retained.size
                  );
                }
                return beginSource(
                  { kind: 'url', url: value },
                  fileName || nameFromUrl(value),
                  mimeType || mimeFromDataUrl(value),
                  Number(expectedBytes)
                );
              };

              const releaseDownload = function(id) {
                sources.delete(String(id || ''));
                return true;
              };

              function dispose() {
                disposed = true;
                document.removeEventListener('click', captureDownloadClick, true);
                if (URL.createObjectURL === wrappedCreateObjectURL) URL.createObjectURL = originalCreateObjectURL;
                if (URL.revokeObjectURL === wrappedRevokeObjectURL) URL.revokeObjectURL = originalRevokeObjectURL;
                if (HTMLAnchorElement.prototype.click === wrappedAnchorClick) {
                  HTMLAnchorElement.prototype.click = originalAnchorClick;
                }
                for (const entry of retainedBlobs.values()) {
                  if (entry.timer !== null) clearTimeout(entry.timer);
                }
                retainedBlobs.clear();
                retainedBlobBytes = 0;
                sources.clear();
              }

              URL.createObjectURL = wrappedCreateObjectURL;
              URL.revokeObjectURL = wrappedRevokeObjectURL;
              HTMLAnchorElement.prototype.click = wrappedAnchorClick;
              document.addEventListener('click', captureDownloadClick, true);
              window.__sillyClientAndroidStartDownload = startDownload;
              window.__sillyClientAndroidRequestUrlDownload = requestUrlDownload;
              window.__sillyClientAndroidReleaseDownload = releaseDownload;
              window[markerName] = { token, dispose };
              return true;
            })();
        """.trimIndent()
    }
}
