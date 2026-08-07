//go:build android && cgo

package main

/*
#include <stdint.h>
#include <stdlib.h>

typedef char* (*androidcyaml_browser_start_callback_t)(
    const char* request_json,
    const void* body,
    int body_length
);
typedef int (*androidcyaml_browser_read_callback_t)(
    int64_t request_id,
    void* buffer,
    int capacity
);
typedef void (*androidcyaml_browser_close_callback_t)(int64_t request_id);

static __attribute__((unused)) char* androidcyaml_call_browser_start(
    void* callback,
    const char* request_json,
    const void* body,
    int body_length
) {
    if (callback == NULL) {
        return NULL;
    }
    return ((androidcyaml_browser_start_callback_t) callback)(
        request_json,
        body,
        body_length
    );
}

static __attribute__((unused)) int androidcyaml_call_browser_read(
    void* callback,
    int64_t request_id,
    void* buffer,
    int capacity
) {
    if (callback == NULL) {
        return -1;
    }
    return ((androidcyaml_browser_read_callback_t) callback)(
        request_id,
        buffer,
        capacity
    );
}

static __attribute__((unused)) void androidcyaml_call_browser_close(
    void* callback,
    int64_t request_id
) {
    if (callback != NULL) {
        ((androidcyaml_browser_close_callback_t) callback)(request_id);
    }
}
*/
import "C"

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"sync"
	"sync/atomic"
	"unsafe"

	"github.com/metacubex/http"
	"github.com/metacubex/http/httptrace"
	"github.com/metacubex/mihomo/transport/xhttp"
)

const maxBrowserRequestBody = 8 << 20

type browserRequest struct {
	Method        string              `json:"method"`
	URL           string              `json:"url"`
	Host          string              `json:"host,omitempty"`
	Headers       map[string][]string `json:"headers,omitempty"`
	ServerAddress string              `json:"serverAddress,omitempty"`
}

type browserStartResult struct {
	ID         int64               `json:"id"`
	StatusCode int                 `json:"status"`
	StatusText string              `json:"statusText,omitempty"`
	Headers    map[string][]string `json:"headers,omitempty"`
	Error      string              `json:"error,omitempty"`
}

var (
	browserCallbackMu    sync.RWMutex
	browserStartCallback unsafe.Pointer
	browserReadCallback  unsafe.Pointer
	browserCloseCallback unsafe.Pointer
)

//export AndroidCyamlInstallBrowserCallbacks
func AndroidCyamlInstallBrowserCallbacks(startValue, readValue, closeValue unsafe.Pointer) {
	browserCallbackMu.Lock()
	browserStartCallback = startValue
	browserReadCallback = readValue
	browserCloseCallback = closeValue
	browserCallbackMu.Unlock()
}

//export AndroidCyamlSetBrowserDialerEnabled
func AndroidCyamlSetBrowserDialerEnabled(enabledValue C.int) *C.char {
	runtimeMu.Lock()
	defer runtimeMu.Unlock()

	if active {
		return respond(nil, errors.New("WebView XHTTP mode can only change while mihomo is stopped"))
	}
	if enabledValue == 0 {
		xhttp.SetBrowserTransportFactory(nil)
		return respond(nil, nil)
	}
	if !browserCallbacksInstalled() {
		return respond(nil, errors.New("Android WebView XHTTP callbacks are not installed"))
	}

	xhttp.SetBrowserTransportFactory(func(options xhttp.BrowserTransportOptions) (http.RoundTripper, error) {
		if !browserCallbacksInstalled() {
			return nil, errors.New("Android WebView XHTTP callbacks became unavailable")
		}
		return &androidBrowserTransport{options: options}, nil
	})
	return respond(nil, nil)
}

type androidBrowserTransport struct {
	options xhttp.BrowserTransportOptions
}

func (t *androidBrowserTransport) RoundTrip(request *http.Request) (*http.Response, error) {
	if request == nil || request.URL == nil {
		return nil, errors.New("WebView XHTTP received an invalid request")
	}
	if request.URL.Scheme != "https" {
		return nil, fmt.Errorf("WebView XHTTP only supports https, got %q", request.URL.Scheme)
	}
	if request.Header.Get("Cookie") != "" || len(request.Cookies()) != 0 {
		return nil, errors.New("WebView XHTTP does not support cookie placement or custom Cookie headers")
	}

	var body []byte
	var err error
	if request.Body != nil {
		body, err = io.ReadAll(io.LimitReader(request.Body, maxBrowserRequestBody+1))
		_ = request.Body.Close()
		if err != nil {
			return nil, fmt.Errorf("read XHTTP request body: %w", err)
		}
		if len(body) > maxBrowserRequestBody {
			return nil, fmt.Errorf("WebView XHTTP request body exceeds %d bytes", maxBrowserRequestBody)
		}
	}

	metadata, err := json.Marshal(browserRequest{
		Method:        request.Method,
		URL:           request.URL.String(),
		Host:          request.Host,
		Headers:       cloneHeader(request.Header),
		ServerAddress: t.options.ServerAddress,
	})
	if err != nil {
		return nil, fmt.Errorf("encode WebView XHTTP request: %w", err)
	}

	startCallback, _, _ := currentBrowserCallbacks()
	if startCallback == nil {
		return nil, errors.New("Android WebView XHTTP start callback is unavailable")
	}
	metadataValue := C.CString(string(metadata))
	defer C.free(unsafe.Pointer(metadataValue))

	var bodyPointer unsafe.Pointer
	if len(body) != 0 {
		bodyPointer = unsafe.Pointer(&body[0])
	}
	// XHTTP packet-up starts the download request before it returns the
	// logical tunnel to its caller. A CDN is allowed to hold that download's
	// response headers until the first upload packet arrives, so waiting for
	// WebView response headers before reporting GotConn deadlocks both sides:
	// mihomo cannot produce the upload until DialPacketUp returns. WebView owns
	// the physical connection and startRequest below accepts/schedules the
	// browser operation synchronously, so release the logical dial immediately
	// before entering that potentially header-blocked call. Any later browser
	// failure is still delivered through RoundTrip and the response body.
	if trace := httptrace.ContextClientTrace(request.Context()); trace != nil && trace.GotConn != nil {
		// mihomo composes this trace with an address collector that expects a
		// non-nil Conn. A pipe supplies stable synthetic addresses without
		// exposing or impersonating WebView's protected physical socket.
		traceConn, tracePeer := net.Pipe()
		trace.GotConn(httptrace.GotConnInfo{Conn: traceConn})
		_ = traceConn.Close()
		_ = tracePeer.Close()
	}
	encoded := C.androidcyaml_call_browser_start(
		startCallback,
		metadataValue,
		bodyPointer,
		C.int(len(body)),
	)
	if encoded == nil {
		return nil, errors.New("Android WebView XHTTP start callback returned null")
	}
	defer C.free(unsafe.Pointer(encoded))

	var result browserStartResult
	if err := json.Unmarshal([]byte(C.GoString(encoded)), &result); err != nil {
		return nil, fmt.Errorf("decode WebView XHTTP response headers: %w", err)
	}
	if result.Error != "" {
		return nil, errors.New(result.Error)
	}
	if result.ID <= 0 {
		return nil, errors.New("Android WebView XHTTP returned an invalid request id")
	}
	if result.StatusCode <= 0 {
		closeBrowserRequest(result.ID)
		return nil, errors.New("Android WebView XHTTP returned an invalid HTTP status")
	}
	status := strconv.Itoa(result.StatusCode)
	if result.StatusText != "" {
		status += " " + result.StatusText
	}
	response := &http.Response{
		Status:        status,
		StatusCode:    result.StatusCode,
		Proto:         "HTTP/2.0",
		ProtoMajor:    2,
		ProtoMinor:    0,
		Header:        make(http.Header),
		Body:          &androidBrowserBody{id: result.ID},
		ContentLength: -1,
		Request:       request,
	}
	for key, values := range result.Headers {
		for _, value := range values {
			response.Header.Add(key, value)
		}
	}
	return response, nil
}

// Chromium owns connection pooling. Closing a logical XHTTP transport must not
// tear down unrelated browser requests.
func (*androidBrowserTransport) CloseIdleConnections() {}

type androidBrowserBody struct {
	id     int64
	closed atomic.Bool
}

func (b *androidBrowserBody) Read(buffer []byte) (int, error) {
	if len(buffer) == 0 {
		return 0, nil
	}
	if b.closed.Load() {
		return 0, io.ErrClosedPipe
	}
	_, readCallback, _ := currentBrowserCallbacks()
	if readCallback == nil {
		return 0, errors.New("Android WebView XHTTP read callback is unavailable")
	}
	count := int(C.androidcyaml_call_browser_read(
		readCallback,
		C.int64_t(b.id),
		unsafe.Pointer(&buffer[0]),
		C.int(len(buffer)),
	))
	switch {
	case count > 0:
		return count, nil
	case count == 0:
		return 0, io.EOF
	default:
		return 0, errors.New("Android WebView XHTTP response stream failed")
	}
}

func (b *androidBrowserBody) Close() error {
	if b.closed.CompareAndSwap(false, true) {
		closeBrowserRequest(b.id)
	}
	return nil
}

func closeBrowserRequest(id int64) {
	_, _, closeCallback := currentBrowserCallbacks()
	if closeCallback != nil && id > 0 {
		C.androidcyaml_call_browser_close(closeCallback, C.int64_t(id))
	}
}

func currentBrowserCallbacks() (unsafe.Pointer, unsafe.Pointer, unsafe.Pointer) {
	browserCallbackMu.RLock()
	defer browserCallbackMu.RUnlock()
	return browserStartCallback, browserReadCallback, browserCloseCallback
}

func browserCallbacksInstalled() bool {
	start, read, closeCallback := currentBrowserCallbacks()
	return start != nil && read != nil && closeCallback != nil
}

func cloneHeader(header http.Header) map[string][]string {
	if len(header) == 0 {
		return nil
	}
	result := make(map[string][]string, len(header))
	for key, values := range header {
		result[key] = append([]string(nil), values...)
	}
	return result
}
