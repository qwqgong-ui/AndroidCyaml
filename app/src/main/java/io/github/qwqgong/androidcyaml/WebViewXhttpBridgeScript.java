package io.github.qwqgong.androidcyaml;

/**
 * The in-page JavaScript that bridges XHTTP fetch()/streaming requests
 * between mihomo's native core and Chromium, shared by every WebView
 * {@link WebViewXhttpDialer} creates.
 */
final class WebViewXhttpBridgeScript {
    private WebViewXhttpBridgeScript() {}

    static final String SOURCE = """
            <!doctype html>
            <meta charset="utf-8">
            <script>
            "use strict";
            (() => {
              const controllers = new Map();
              const pendingBodyReads = new Map();
              const textEncoder = new TextEncoder();
              const textDecoder = new TextDecoder();
              let nextBodyReadId = 1;
              let bridgePort = null;
              const downlinkFramingHeader = "X-AndroidCyaml-XHTTP-Framing";
              const downlinkFramingValue = "v1";
              const downlinkDataFrame = 0;
              const downlinkHeartbeatFrame = 1;
              const maxDownlinkFrameBytes = 16 * 1024 * 1024;
              const forbidden = new Set([
                "host", "content-length", "transfer-encoding", "connection",
                "upgrade", "proxy-connection", "proxy-authorization",
                "user-agent", "accept-encoding", "origin", "cookie"
              ]);

              const requestStreamingSupported = (() => {
                try {
                  if (typeof fetch !== "function" ||
                      typeof Request !== "function" ||
                      typeof ReadableStream !== "function") {
                    return false;
                  }
                  let duplexAccessed = false;
                  const probe = new Request(document.baseURI, {
                    method: "POST",
                    body: new ReadableStream(),
                    get duplex() {
                      duplexAccessed = true;
                      return "half";
                    }
                  });
                  const supported = duplexAccessed &&
                    probe.body !== null &&
                    !probe.headers.has("Content-Type");
                  if (probe.body) probe.body.cancel().catch(() => {});
                  return supported;
                } catch (_) {
                  return false;
                }
              })();

              function decodeBase64(value) {
                const binary = atob(value);
                const output = new Uint8Array(binary.length);
                for (let i = 0; i < binary.length; i++) output[i] = binary.charCodeAt(i);
                return output;
              }

              function encodeBase64(value) {
                let binary = "";
                const step = 0x8000;
                for (let offset = 0; offset < value.length; offset += step) {
                  const part = value.subarray(offset, Math.min(value.length, offset + step));
                  binary += String.fromCharCode.apply(null, part);
                }
                return btoa(binary);
              }

              function readBridgeId(view, offset) {
                return view.getUint32(offset, false) * 0x100000000 +
                  view.getUint32(offset + 4, false);
              }

              function writeBridgeId(view, offset, value) {
                view.setUint32(offset, Math.floor(value / 0x100000000), false);
                view.setUint32(offset + 4, value % 0x100000000, false);
              }

              function deliverRequestBodyChunk(bodyId, readId, status, chunk) {
                const pending = pendingBodyReads.get(readId);
                if (!pending || pending.bodyId !== bodyId) return;
                pendingBodyReads.delete(readId);
                try {
                  if (status < 0) {
                    pending.controller.error(new Error("request body bridge failed"));
                  } else if (status === 0) {
                    pending.controller.close();
                  } else {
                    pending.controller.enqueue(chunk);
                  }
                } catch (_) {
                  // The stream may have been canceled while the native read was in flight.
                } finally {
                  pending.resolve();
                }
              }

              function onBridgeMessage(event) {
                const data = event.data;
                const bytes = data instanceof ArrayBuffer
                  ? new Uint8Array(data)
                  : (ArrayBuffer.isView(data)
                    ? new Uint8Array(data.buffer, data.byteOffset, data.byteLength)
                    : null);
                if (!bytes || !bytes.byteLength) return;
                if (bytes[0] === 1) {
                  if (bytes.byteLength < 18) return;
                  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
                  const bodyId = readBridgeId(view, 1);
                  const readId = readBridgeId(view, 9);
                  const status = view.getInt8(17);
                  deliverRequestBodyChunk(bodyId, readId, status, bytes.slice(18));
                } else if (bytes[0] === 5 && bytes.byteLength > 1) {
                  window.androidCyamlStart(textDecoder.decode(bytes.subarray(1)));
                }
              }

              window.addEventListener("message", event => {
                if (event.data !== "androidcyaml-binary-v1" ||
                    !event.ports || !event.ports.length || bridgePort) {
                  return;
                }
                bridgePort = event.ports[0];
                bridgePort.onmessage = onBridgeMessage;
                bridgePort.start();
                AndroidCyamlNative.onMessagePortReady();
              });

              window.androidCyamlDisableBinaryBridge = () => {
                const current = bridgePort;
                bridgePort = null;
                if (current) {
                  try { current.close(); } catch (_) {}
                }
              };

              function postResponseChunk(id, value) {
                if (bridgePort) {
                  try {
                    const frame = new Uint8Array(9 + value.byteLength);
                    const view = new DataView(frame.buffer);
                    frame[0] = 2;
                    writeBridgeId(view, 1, id);
                    frame.set(value, 9);
                    bridgePort.postMessage(frame.buffer, [frame.buffer]);
                    return;
                  } catch (_) {
                    bridgePort = null;
                  }
                }
                AndroidCyamlNative.onChunk(id, encodeBase64(value));
              }

              function postResponseTerminal(id, error) {
                if (bridgePort) {
                  try {
                    const payload = error ? textEncoder.encode(error) : new Uint8Array(0);
                    const frame = new Uint8Array(9 + payload.byteLength);
                    const view = new DataView(frame.buffer);
                    frame[0] = error ? 4 : 3;
                    writeBridgeId(view, 1, id);
                    frame.set(payload, 9);
                    bridgePort.postMessage(frame.buffer, [frame.buffer]);
                    return;
                  } catch (_) {
                    bridgePort = null;
                  }
                }
                if (error) {
                  AndroidCyamlNative.onError(id, error);
                } else {
                  AndroidCyamlNative.onComplete(id);
                }
              }

              function postResponseHeaders(id, status, statusText, headers) {
                if (bridgePort) {
                  try {
                    const payload = textEncoder.encode(JSON.stringify({
                      status,
                      statusText: statusText || "",
                      headers
                    }));
                    const frame = new Uint8Array(9 + payload.byteLength);
                    const view = new DataView(frame.buffer);
                    frame[0] = 6;
                    writeBridgeId(view, 1, id);
                    frame.set(payload, 9);
                    bridgePort.postMessage(frame.buffer, [frame.buffer]);
                    return;
                  } catch (_) {
                    bridgePort = null;
                  }
                }
                AndroidCyamlNative.onHeaders(
                  id,
                  status,
                  statusText || "",
                  JSON.stringify(headers)
                );
              }

              function bufferedRequestBody(id) {
                const length = AndroidCyamlNative.requestBodyLength(id);
                if (length <= 0) return null;
                const output = new Uint8Array(length);
                let offset = 0;
                while (offset < length) {
                  const encoded = AndroidCyamlNative.requestBodyChunk(id, offset, 49152);
                  if (!encoded) throw new Error("request body bridge ended early");
                  const part = decodeBase64(encoded);
                  output.set(part, offset);
                  offset += part.length;
                }
                return output;
              }

              function streamingRequestBody(bodyId) {
                return new ReadableStream({
                  pull(controller) {
                    return new Promise(resolve => {
                      const readId = nextBodyReadId++;
                      pendingBodyReads.set(readId, { bodyId, controller, resolve });
                      try {
                        AndroidCyamlNative.readRequestBodyChunk(bodyId, readId, 49152);
                      } catch (error) {
                        pendingBodyReads.delete(readId);
                        try {
                          controller.error(error);
                        } finally {
                          resolve();
                        }
                      }
                    });
                  },
                  cancel() {
                    cancelPendingBodyReads(bodyId);
                    AndroidCyamlNative.closeRequestBody(bodyId);
                  }
                }, { highWaterMark: 1 });
              }

              function cancelPendingBodyReads(bodyId) {
                for (const [readId, pending] of pendingBodyReads) {
                  if (pending.bodyId !== bodyId) continue;
                  pendingBodyReads.delete(readId);
                  pending.resolve();
                }
              }

              window.androidCyamlBodyChunk = (bodyId, readId, status, encoded) => {
                deliverRequestBodyChunk(
                  bodyId,
                  readId,
                  status,
                  status > 0 ? decodeBase64(encoded) : new Uint8Array(0)
                );
              };

              function prepare(meta) {
                const init = {
                  method: meta.method,
                  cache: "no-store",
                  credentials: "include",
                  redirect: "follow"
                };
                const headers = new Headers();
                let referrer = "";
                for (const [name, values] of Object.entries(meta.headers || {})) {
                  const lower = name.toLowerCase();
                  if (lower === "referer") {
                    if (values && values.length) referrer = values[0];
                    continue;
                  }
                  if (forbidden.has(lower) || lower.startsWith("sec-") || lower.startsWith("proxy-")) {
                    continue;
                  }
                  for (const value of values || []) {
                    try { headers.append(name, value); } catch (_) {}
                  }
                }
                if (meta.method === "GET") {
                  headers.set(downlinkFramingHeader, downlinkFramingValue);
                }
                init.headers = headers;
                if (referrer) {
                  try {
                    const parsed = new URL(referrer);
                    init.referrer = new URL(parsed.pathname + parsed.search + parsed.hash, document.baseURI).href;
                    init.referrerPolicy = "unsafe-url";
                  } catch (_) {}
                }
                if (meta.method !== "GET" && meta.method !== "HEAD") {
                  if (meta.bodyId > 0) {
                    if (!requestStreamingSupported) {
                      throw new Error("streaming request bodies are unavailable");
                    }
                    init.body = streamingRequestBody(meta.bodyId);
                    init.duplex = "half";
                  } else {
                    const body = bufferedRequestBody(meta.id);
                    if (body && body.length) init.body = body;
                  }
                }
                return init;
              }

              async function streamResponseBody(id, response, framed) {
                const reader = response.body.getReader();
                if (!framed) {
                  while (true) {
                    const result = await reader.read();
                    if (result.value && result.value.byteLength) {
                      postResponseChunk(id, result.value);
                    }
                    if (result.done) return;
                  }
                }

                let pending = new Uint8Array(0);
                while (true) {
                  const result = await reader.read();
                  if (result.value && result.value.byteLength) {
                    const combined = new Uint8Array(pending.byteLength + result.value.byteLength);
                    combined.set(pending);
                    combined.set(result.value, pending.byteLength);
                    pending = combined;
                  }

                  let offset = 0;
                  while (pending.byteLength - offset >= 5) {
                    const view = new DataView(
                      pending.buffer,
                      pending.byteOffset + offset,
                      pending.byteLength - offset
                    );
                    const type = view.getUint8(0);
                    const length = view.getUint32(1, false);
                    if (length > maxDownlinkFrameBytes) {
                      throw new Error("XHTTP downlink frame is too large");
                    }
                    if (pending.byteLength - offset < 5 + length) break;
                    if (type === downlinkDataFrame) {
                      if (length) {
                        postResponseChunk(id, pending.slice(offset + 5, offset + 5 + length));
                      }
                    } else if (type !== downlinkHeartbeatFrame || length !== 0) {
                      throw new Error("Invalid XHTTP downlink frame");
                    }
                    offset += 5 + length;
                  }
                  if (offset) pending = pending.slice(offset);
                  if (result.done) {
                    if (pending.byteLength) {
                      throw new Error("Truncated XHTTP downlink frame");
                    }
                    return;
                  }
                }
              }

              window.androidCyamlStart = async encoded => {
                const meta = JSON.parse(encoded);
                const controller = new AbortController();
                const bodyId = Number(meta.bodyId) || 0;
                const active = { controller, bodyId };
                controllers.set(meta.id, active);
                try {
                  const init = prepare(meta);
                  init.signal = controller.signal;
                  const response = await fetch(meta.url, init);
                  const headers = {};
                  response.headers.forEach((value, name) => { headers[name] = [value]; });
                  postResponseHeaders(
                    meta.id,
                    response.status,
                    response.statusText,
                    headers
                  );
                  if (!response.body) {
                    postResponseTerminal(meta.id, "");
                    return;
                  }
                  const framed = meta.method === "GET" &&
                    response.headers.get(downlinkFramingHeader) === downlinkFramingValue;
                  await streamResponseBody(meta.id, response, framed);
                  postResponseTerminal(meta.id, "");
                } catch (error) {
                  postResponseTerminal(
                    meta.id,
                    String(error && error.message ? error.message : error)
                  );
                } finally {
                  if (bodyId > 0) {
                    cancelPendingBodyReads(bodyId);
                    AndroidCyamlNative.closeRequestBody(bodyId);
                  }
                  controllers.delete(meta.id);
                }
              };

              window.androidCyamlCancel = id => {
                const active = controllers.get(id);
                if (active) {
                  active.controller.abort();
                  if (active.bodyId > 0) {
                    cancelPendingBodyReads(active.bodyId);
                    AndroidCyamlNative.closeRequestBody(active.bodyId);
                  }
                }
                controllers.delete(id);
              };

              AndroidCyamlNative.onReady(requestStreamingSupported);
            })();
            </script>
            """;
}
