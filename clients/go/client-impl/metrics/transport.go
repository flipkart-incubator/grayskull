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

	// Record metrics for both successful and failed requests
	path := NormalizeURL(req.URL.String())
	if resp != nil {
		t.Recorder.RecordRequest(path, resp.StatusCode, duration)
	} else if err != nil {
		// Record failed request with status code 0
		t.Recorder.RecordRequest(path, 0, duration)
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
func NewTransport(transport http.RoundTripper, recorder Recorder) *Transport {
	return &Transport{
		Transport: transport,
		Recorder:  recorder,
	}
}
