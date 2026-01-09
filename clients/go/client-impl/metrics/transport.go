package metrics

import (
	"net/http"
	"time"
)

// Transport is an http.RoundTripper that records metrics
type Transport struct {
	Transport http.RoundTripper
	Recorder  Recorder
}

// RoundTrip executes a single HTTP transaction and records metrics
func (t *Transport) RoundTrip(req *http.Request) (*http.Response, error) {
	start := time.Now()

	// Make the request
	resp, err := t.transport().RoundTrip(req)

	// Calculate duration
	duration := time.Since(start).Milliseconds()

	// Record metrics if we have a response
	if resp != nil {
		path := NormalizeURL(req.URL.Path)
		t.Recorder.RecordRequest(path, resp.StatusCode, duration)
	}

	return resp, err
}

func (t *Transport) transport() http.RoundTripper {
	if t.Transport != nil {
		return t.Transport
	}
	return http.DefaultTransport
}

// NewTransport creates a new metrics transport
func NewTransport(transport *http.Transport, recorder Recorder) *Transport {
	return &Transport{
		Recorder: recorder,
	}
}
