package internal_test

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"

	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/models"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type staticAuthForDoPost struct{}

func (staticAuthForDoPost) GetAuthHeader() (string, error) { return "Bearer test-token", nil }

func newDoPostClient(t *testing.T, handler http.HandlerFunc) (internal.HeartbeatPoster, string) {
	t.Helper()
	ts := httptest.NewServer(handler)
	t.Cleanup(ts.Close)
	config := models.NewDefaultConfig()
	config.Host = ts.URL
	config.MaxRetries = 3
	client := internal.NewGrayskullHTTPClient(staticAuthForDoPost{}, config, nil, NewNoopRecorder())
	t.Cleanup(func() { _ = client.Close() })
	poster, ok := client.(internal.HeartbeatPoster)
	require.True(t, ok, "the SDK HTTP client must support single-attempt POSTs")
	return poster, ts.URL
}

func TestDoPost_SendsBodyAndHeadersOnce(t *testing.T) {
	var calls atomic.Int32
	var gotBody, gotAuth, gotType string
	poster, base := newDoPostClient(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		b, _ := io.ReadAll(r.Body)
		gotBody, gotAuth, gotType = string(b), r.Header.Get("Authorization"), r.Header.Get("Content-Type")
		w.WriteHeader(http.StatusNoContent)
	})

	status, err := poster.DoPost(context.Background(), base+"/v1/client/heartbeat", []byte(`{"secrets":[]}`))
	require.NoError(t, err)
	assert.Equal(t, http.StatusNoContent, status)
	assert.EqualValues(t, 1, calls.Load())
	assert.Equal(t, `{"secrets":[]}`, gotBody)
	assert.Equal(t, "Bearer test-token", gotAuth)
	assert.Contains(t, gotType, "application/json")
}

func TestDoPost_DoesNotRetryAndReturnsStatus(t *testing.T) {
	for _, status := range []int{http.StatusNotFound, http.StatusServiceUnavailable} {
		var calls atomic.Int32
		poster, base := newDoPostClient(t, func(w http.ResponseWriter, _ *http.Request) {
			calls.Add(1)
			w.WriteHeader(status)
		})

		got, err := poster.DoPost(context.Background(), base+"/v1/client/heartbeat", []byte(`{}`))
		require.Error(t, err)
		assert.Equal(t, status, got)
		assert.EqualValues(t, 1, calls.Load(), "status %d: single attempt even when retryable", status)
	}
}
