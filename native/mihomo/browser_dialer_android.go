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
typedef char* (*androidcyaml_browser_headers_callback_t)(int64_t request_id);
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

static __attribute__((unused)) char* androidcyaml_call_browser_headers(
    void* callback,
    int64_t request_id
) {
    if (callback == NULL) {
        return NULL;
    }
    return ((androidcyaml_browser_headers_callback_t) callback)(request_id);
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
	"context"
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
	core "github.com/metacubex/mihomo/androidcyaml"
)

const maxBrowserRequestBody = 8 << 20

// Awaiting WebView response headers crosses into Java and can block for tens of
// seconds. Bound those cgo calls independently so WebView congestion cannot
// consume an unbounded number of Go M threads. Close callbacks remain
// unbounded and can therefore always cancel an admitted request.
const maxConcurrentBrowserHeaderCallbacks = 16

type browserRequest struct {
	ID            int64               `json:"id"`
	Method        string              `json:"method"`
	URL           string              `json:"url"`
	Host          string              `json:"host,omitempty"`
	Headers       map[string][]string `json:"headers,omitempty"`
	ServerAddress string              `json:"serverAddress,omitempty"`
	BodyID        int64               `json:"bodyId,omitempty"`
}

type browserStartResult struct {
	ID    int64  `json:"id"`
	Error string `json:"error,omitempty"`
}

type browserHeadersResult struct {
	StatusCode int                 `json:"status"`
	StatusText string              `json:"statusText,omitempty"`
	Headers    map[string][]string `json:"headers,omitempty"`
	Error      string              `json:"error,omitempty"`
}

var (
	browserCallbackMu            sync.RWMutex
	browserStartCallback         unsafe.Pointer
	browserHeadersCallback       unsafe.Pointer
	browserReadCallback          unsafe.Pointer
	browserCloseCallback         unsafe.Pointer
	browserRequestStreamsEnabled atomic.Bool
	browserRequestBodyMu         sync.Mutex
	browserRequestBodies         = make(map[int64]*browserRequestBody)
	nextBrowserRequestBodyID     atomic.Int64
	browserResponseMu            sync.Mutex
	browserResponses             = make(map[int64]*browserResponseStream)
	nextBrowserRequestID         atomic.Int64
	browserHeaderCallbackLimit   = newCallbackLimiter(maxConcurrentBrowserHeaderCallbacks)
)

const browserResponseQueueSize = 8

type browserResponseChunk struct {
	data []byte
	err  error
	eof  bool
}

type browserResponseStream struct {
	chunks   chan browserResponseChunk
	done     chan struct{}
	terminal atomic.Bool
	closed   atomic.Bool
	readMu   sync.Mutex
	current  []byte
}

func newBrowserResponseStream() *browserResponseStream {
	return &browserResponseStream{
		chunks: make(chan browserResponseChunk, browserResponseQueueSize),
		done:   make(chan struct{}),
	}
}

func (s *browserResponseStream) push(data []byte) bool {
	if len(data) == 0 || s.closed.Load() || s.terminal.Load() {
		return len(data) == 0 && !s.closed.Load()
	}
	chunk := browserResponseChunk{data: append([]byte(nil), data...)}
	select {
	case s.chunks <- chunk:
		return true
	case <-s.done:
		return false
	}
}

func (s *browserResponseStream) finish(err error) {
	if !s.terminal.CompareAndSwap(false, true) {
		return
	}
	terminal := browserResponseChunk{err: err, eof: err == nil}
	select {
	case s.chunks <- terminal:
	case <-s.done:
	}
}

func (s *browserResponseStream) read(buffer []byte) (int, error) {
	if len(buffer) == 0 {
		return 0, nil
	}
	s.readMu.Lock()
	defer s.readMu.Unlock()

	for {
		if len(s.current) > 0 {
			count := copy(buffer, s.current)
			s.current = s.current[count:]
			return count, nil
		}
		select {
		case chunk := <-s.chunks:
			if chunk.err != nil {
				return 0, chunk.err
			}
			if chunk.eof {
				return 0, io.EOF
			}
			s.current = chunk.data
		case <-s.done:
			return 0, io.ErrClosedPipe
		}
	}
}

func (s *browserResponseStream) close() {
	if s.closed.CompareAndSwap(false, true) {
		close(s.done)
	}
}

//export AndroidCyamlInstallBrowserCallbacks
func AndroidCyamlInstallBrowserCallbacks(startValue, headersValue, readValue, closeValue unsafe.Pointer) {
	browserCallbackMu.Lock()
	browserStartCallback = startValue
	browserHeadersCallback = headersValue
	browserReadCallback = readValue
	browserCloseCallback = closeValue
	browserCallbackMu.Unlock()
}

//export AndroidCyamlSetBrowserDialerEnabled
func AndroidCyamlSetBrowserDialerEnabled(enabledValue, requestStreamsValue C.int) *C.char {
	runtimeMu.Lock()
	defer runtimeMu.Unlock()

	if active {
		return respond(nil, errors.New("WebView XHTTP mode can only change while mihomo is stopped"))
	}
	if enabledValue == 0 {
		browserRequestStreamsEnabled.Store(false)
		closeAllBrowserRequestBodies()
		closeAllBrowserResponses()
		core.ResetBrowserTransport()
		return respond(nil, nil)
	}
	if !browserCallbacksInstalled() {
		return respond(nil, errors.New("Android WebView XHTTP callbacks are not installed"))
	}

	supportsRequestStreams := requestStreamsValue != 0
	browserRequestStreamsEnabled.Store(supportsRequestStreams)
	core.SetBrowserTransport(func(options core.BrowserTransportOptions) (http.RoundTripper, error) {
		if !browserCallbacksInstalled() {
			return nil, errors.New("Android WebView XHTTP callbacks became unavailable")
		}
		return &androidBrowserTransport{options: options}, nil
	}, supportsRequestStreams)
	return respond(nil, nil)
}

type androidBrowserTransport struct {
	options core.BrowserTransportOptions
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

	var (
		body   []byte
		bodyID int64
	)
	var err error
	streamBody := request.Body != nil && request.Body != http.NoBody && request.ContentLength <= 0
	if streamBody {
		if !browserRequestStreamsEnabled.Load() {
			_ = request.Body.Close()
			return nil, errors.New("Android WebView does not support streaming request bodies")
		}
		bodyID, err = registerBrowserRequestBody(request.Body)
		if err != nil {
			_ = request.Body.Close()
			return nil, err
		}
		defer closeBrowserRequestBody(bodyID)
	} else if request.Body != nil {
		body, err = io.ReadAll(io.LimitReader(request.Body, maxBrowserRequestBody+1))
		_ = request.Body.Close()
		if err != nil {
			return nil, fmt.Errorf("read XHTTP request body: %w", err)
		}
		if len(body) > maxBrowserRequestBody {
			return nil, fmt.Errorf("WebView XHTTP request body exceeds %d bytes", maxBrowserRequestBody)
		}
	}

	requestID, responseStream, err := registerBrowserResponse()
	if err != nil {
		return nil, err
	}
	keepBrowserResponse := false
	defer func() {
		if !keepBrowserResponse {
			closeBrowserResponse(requestID)
		}
	}()

	metadata, err := json.Marshal(browserRequest{
		ID:            requestID,
		Method:        request.Method,
		URL:           request.URL.String(),
		Host:          request.Host,
		Headers:       cloneHeader(request.Header),
		ServerAddress: t.options.ServerAddress,
		BodyID:        bodyID,
	})
	if err != nil {
		return nil, fmt.Errorf("encode WebView XHTTP request: %w", err)
	}

	startCallback, _, _, _ := currentBrowserCallbacks()
	if startCallback == nil {
		return nil, errors.New("Android WebView XHTTP start callback is unavailable")
	}
	metadataValue := C.CString(string(metadata))
	defer C.free(unsafe.Pointer(metadataValue))

	var bodyPointer unsafe.Pointer
	if len(body) != 0 {
		bodyPointer = unsafe.Pointer(&body[0])
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
		return nil, fmt.Errorf("decode WebView XHTTP request start: %w", err)
	}
	if result.Error != "" {
		return nil, errors.New(result.Error)
	}
	if result.ID != requestID {
		return nil, errors.New("Android WebView XHTTP returned an invalid request id")
	}

	// Java returns after the fetch has been queued on the WebView main thread,
	// before waiting for response headers. Report the logical connection only
	// now: DialStreamUp will enqueue its upload POST after the download GET, and
	// packet-up still avoids a CDN header-buffering deadlock.
	if trace := httptrace.ContextClientTrace(request.Context()); trace != nil && trace.GotConn != nil {
		traceConn, tracePeer := net.Pipe()
		trace.GotConn(httptrace.GotConnInfo{Conn: traceConn})
		_ = traceConn.Close()
		_ = tracePeer.Close()
	}

	stopCancel := context.AfterFunc(request.Context(), func() {
		closeBrowserRequest(result.ID)
	})
	if err := request.Context().Err(); err != nil {
		stopCancel()
		closeBrowserRequest(result.ID)
		return nil, err
	}
	_, headersCallback, _, _ := currentBrowserCallbacks()
	if headersCallback == nil {
		stopCancel()
		closeBrowserRequest(result.ID)
		return nil, errors.New("Android WebView XHTTP headers callback is unavailable")
	}
	headerValue := withCallbackPermit(browserHeaderCallbackLimit, func() *C.char {
		return C.androidcyaml_call_browser_headers(
			headersCallback,
			C.int64_t(result.ID),
		)
	})
	if headerValue == nil {
		stopCancel()
		closeBrowserRequest(result.ID)
		return nil, errors.New("Android WebView XHTTP headers callback returned null")
	}
	defer C.free(unsafe.Pointer(headerValue))
	if err := request.Context().Err(); err != nil {
		stopCancel()
		closeBrowserRequest(result.ID)
		return nil, err
	}

	var headersResult browserHeadersResult
	if err := json.Unmarshal([]byte(C.GoString(headerValue)), &headersResult); err != nil {
		stopCancel()
		closeBrowserRequest(result.ID)
		return nil, fmt.Errorf("decode WebView XHTTP response headers: %w", err)
	}
	if headersResult.Error != "" {
		stopCancel()
		closeBrowserRequest(result.ID)
		return nil, errors.New(headersResult.Error)
	}
	if headersResult.StatusCode <= 0 {
		stopCancel()
		closeBrowserRequest(result.ID)
		return nil, errors.New("Android WebView XHTTP returned an invalid HTTP status")
	}
	status := strconv.Itoa(headersResult.StatusCode)
	if headersResult.StatusText != "" {
		status += " " + headersResult.StatusText
	}
	response := &http.Response{
		Status:     status,
		StatusCode: headersResult.StatusCode,
		Proto:      "HTTP/2.0",
		ProtoMajor: 2,
		ProtoMinor: 0,
		Header:     make(http.Header),
		Body: &androidBrowserBody{
			id:         result.ID,
			stream:     responseStream,
			stopCancel: stopCancel,
		},
		ContentLength: -1,
		Request:       request,
	}
	for key, values := range headersResult.Headers {
		for _, value := range values {
			response.Header.Add(key, value)
		}
	}
	keepBrowserResponse = true
	return response, nil
}

// Chromium owns connection pooling. Closing a logical XHTTP transport must not
// tear down unrelated browser requests.
func (*androidBrowserTransport) CloseIdleConnections() {}

type androidBrowserBody struct {
	id         int64
	stream     *browserResponseStream
	stopCancel func() bool
	closed     atomic.Bool
}

func (b *androidBrowserBody) Read(buffer []byte) (int, error) {
	if len(buffer) == 0 {
		return 0, nil
	}
	if b.closed.Load() || b.stream == nil {
		return 0, io.ErrClosedPipe
	}
	return b.stream.read(buffer)
}

func (b *androidBrowserBody) Close() error {
	if b.closed.CompareAndSwap(false, true) {
		if b.stopCancel != nil {
			b.stopCancel()
		}
		closeBrowserRequest(b.id)
		closeBrowserResponse(b.id)
	}
	return nil
}

func closeBrowserRequest(id int64) {
	_, _, _, closeCallback := currentBrowserCallbacks()
	if closeCallback != nil && id > 0 {
		C.androidcyaml_call_browser_close(closeCallback, C.int64_t(id))
	}
}

func registerBrowserResponse() (int64, *browserResponseStream, error) {
	id := nextBrowserRequestID.Add(1)
	if id <= 0 {
		return 0, nil, errors.New("Android WebView XHTTP exhausted request ids")
	}
	stream := newBrowserResponseStream()
	browserResponseMu.Lock()
	browserResponses[id] = stream
	browserResponseMu.Unlock()
	return id, stream, nil
}

func currentBrowserResponse(id int64) *browserResponseStream {
	browserResponseMu.Lock()
	stream := browserResponses[id]
	browserResponseMu.Unlock()
	return stream
}

func closeBrowserResponse(id int64) {
	if id <= 0 {
		return
	}
	browserResponseMu.Lock()
	stream := browserResponses[id]
	delete(browserResponses, id)
	browserResponseMu.Unlock()
	if stream != nil {
		stream.close()
	}
}

func closeAllBrowserResponses() {
	browserResponseMu.Lock()
	responses := browserResponses
	browserResponses = make(map[int64]*browserResponseStream)
	browserResponseMu.Unlock()
	for _, stream := range responses {
		stream.close()
	}
}

//export AndroidCyamlPushBrowserResponseChunk
func AndroidCyamlPushBrowserResponseChunk(
	idValue C.int64_t,
	buffer unsafe.Pointer,
	lengthValue C.int,
) C.int {
	length := int(lengthValue)
	if idValue <= 0 || buffer == nil || length <= 0 {
		return 0
	}
	stream := currentBrowserResponse(int64(idValue))
	if stream == nil || !stream.push(unsafe.Slice((*byte)(buffer), length)) {
		return 0
	}
	return 1
}

//export AndroidCyamlFinishBrowserResponse
func AndroidCyamlFinishBrowserResponse(idValue C.int64_t, errorValue *C.char) C.int {
	if idValue <= 0 {
		return 0
	}
	stream := currentBrowserResponse(int64(idValue))
	if stream == nil {
		return 0
	}
	var err error
	if errorValue != nil {
		message := C.GoString(errorValue)
		if message != "" {
			err = errors.New(message)
		}
	}
	stream.finish(err)
	return 1
}

func currentBrowserCallbacks() (unsafe.Pointer, unsafe.Pointer, unsafe.Pointer, unsafe.Pointer) {
	browserCallbackMu.RLock()
	defer browserCallbackMu.RUnlock()
	return browserStartCallback, browserHeadersCallback, browserReadCallback, browserCloseCallback
}

func browserCallbacksInstalled() bool {
	start, headers, read, closeCallback := currentBrowserCallbacks()
	return start != nil && headers != nil && read != nil && closeCallback != nil
}

type browserRequestBody struct {
	reader   io.ReadCloser
	readMu   sync.Mutex
	closed   atomic.Bool
	terminal atomic.Int32
}

func (b *browserRequestBody) read(buffer []byte) int {
	b.readMu.Lock()
	defer b.readMu.Unlock()

	for {
		if b.closed.Load() {
			return -1
		}
		switch b.terminal.Load() {
		case 1:
			return 0
		case -1:
			return -1
		}
		count, err := b.reader.Read(buffer)
		if err != nil {
			if errors.Is(err, io.EOF) {
				b.terminal.Store(1)
			} else {
				b.terminal.Store(-1)
			}
		}
		if count > 0 {
			return count
		}
		if err != nil {
			continue
		}
	}
}

func (b *browserRequestBody) close() {
	if b.closed.CompareAndSwap(false, true) {
		_ = b.reader.Close()
	}
}

func registerBrowserRequestBody(reader io.ReadCloser) (int64, error) {
	id := nextBrowserRequestBodyID.Add(1)
	if id <= 0 {
		return 0, errors.New("Android WebView XHTTP exhausted request body ids")
	}
	browserRequestBodyMu.Lock()
	browserRequestBodies[id] = &browserRequestBody{reader: reader}
	browserRequestBodyMu.Unlock()
	return id, nil
}

func closeBrowserRequestBody(id int64) {
	if id <= 0 {
		return
	}
	browserRequestBodyMu.Lock()
	body := browserRequestBodies[id]
	delete(browserRequestBodies, id)
	browserRequestBodyMu.Unlock()
	if body != nil {
		body.close()
	}
}

func closeAllBrowserRequestBodies() {
	browserRequestBodyMu.Lock()
	bodies := browserRequestBodies
	browserRequestBodies = make(map[int64]*browserRequestBody)
	browserRequestBodyMu.Unlock()
	for _, body := range bodies {
		body.close()
	}
}

//export AndroidCyamlReadBrowserRequestBody
func AndroidCyamlReadBrowserRequestBody(idValue C.int64_t, buffer unsafe.Pointer, capacityValue C.int) C.int {
	capacity := int(capacityValue)
	if idValue <= 0 || buffer == nil || capacity <= 0 {
		return -1
	}
	browserRequestBodyMu.Lock()
	body := browserRequestBodies[int64(idValue)]
	browserRequestBodyMu.Unlock()
	if body == nil {
		return -1
	}
	return C.int(body.read(unsafe.Slice((*byte)(buffer), capacity)))
}

//export AndroidCyamlCloseBrowserRequestBody
func AndroidCyamlCloseBrowserRequestBody(idValue C.int64_t) {
	closeBrowserRequestBody(int64(idValue))
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
