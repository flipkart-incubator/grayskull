package internal_test

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	apiconstants "github.com/flipkart-incubator/grayskull/clients/go/client-api/constants"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/models"
)

func TestDoPostWithRetry_SuccessRoundTrip(t *testing.T) {
	type req struct {
		Hello string `json:"hello"`
	}
	type resp struct {
		Echo string `json:"echo"`
	}

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPost, r.Method)
		assert.Equal(t, "application/json; charset=utf-8", r.Header.Get("Content-Type"))
		body, err := io.ReadAll(r.Body)
		require.NoError(t, err)

		var in req
		require.NoError(t, json.Unmarshal(body, &in))
		out, _ := json.Marshal(resp{Echo: in.Hello})
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(out)
	}))
	t.Cleanup(srv.Close)

	cfg := &models.GrayskullClientConfiguration{MaxRetries: 3, MinRetryDelay: 50, ReadTimeout: 1000}
	client := setupClient(t, cfg)

	body, _ := json.Marshal(req{Hello: "world"})
	var out resp
	statusCode, err := client.DoPostWithRetry(context.Background(), srv.URL, body, &out)
	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, statusCode)
	assert.Equal(t, "world", out.Echo)
}

// TestDoPostWithRetry_DefaultHeadersAreSent verifies that headers set on the
// configuration via AddDefaultHeader are attached to every outbound request.
func TestDoPostWithRetry_DefaultHeadersAreSent(t *testing.T) {
	var gotWorkload, gotUserAgent atomic.Value

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotWorkload.Store(r.Header.Get(apiconstants.WorkloadHeader))
		gotUserAgent.Store(r.Header.Get(apiconstants.UserAgentHeader))
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{}`))
	}))
	t.Cleanup(srv.Close)

	cfg := &models.GrayskullClientConfiguration{MaxRetries: 3, MinRetryDelay: 50, ReadTimeout: 1000}
	cfg.AddDefaultHeader(apiconstants.WorkloadHeader, "my-host-7")
	cfg.AddDefaultHeader(apiconstants.UserAgentHeader, "grayskull-go/0.0.0-test")
	client := setupClientWithConfig(t, cfg)

	_, err := client.DoPostWithRetry(context.Background(), srv.URL, []byte(`{}`), nil)
	require.NoError(t, err)

	assert.Equal(t, "my-host-7", gotWorkload.Load(), "Grayskull-Workload header must be sent")
	assert.Equal(t, "grayskull-go/0.0.0-test", gotUserAgent.Load(), "User-Agent header must be sent")
}

// setupClientWithConfig is a small wrapper around the existing setupClient
// helper that does not overwrite the caller's configuration.
func setupClientWithConfig(t *testing.T, cfg *models.GrayskullClientConfiguration) internal.GrayskullHTTPClientInterface {
	t.Helper()
	mockAuth := &MockAuthProviderHTTP{}
	mockAuth.On("GetAuthHeader").Return("Bearer test-token", nil)
	return internal.NewGrayskullHTTPClient(mockAuth, cfg, nil, NewNoopRecorder())
}
