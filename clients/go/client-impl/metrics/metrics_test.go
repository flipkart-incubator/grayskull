package metrics

import (
	"bytes"
	"fmt"
	"log/slog"
	"sync"
	"testing"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestMetricsRecorder_E2E_BasicWorkflow(t *testing.T) {
	recorder := NewPrometheusRecorder()
	require.NotNil(t, recorder)

	t.Run("recorder has correct name", func(t *testing.T) {
		assert.Equal(t, "prometheus", recorder.GetRecorderName())
	})

	t.Run("can record requests successfully", func(t *testing.T) {
		recorder.RecordRequest("api/users", 200, 150*time.Millisecond)
		recorder.RecordRequest("api/users", 200, 200*time.Millisecond)
		recorder.RecordRequest("api/posts", 404, 50*time.Millisecond)
		recorder.RecordRequest("api/posts", 500, 300*time.Millisecond)
	})

	t.Run("can record retries successfully", func(t *testing.T) {
		recorder.RecordRetry("http://api.example.com/users", 1, false)
		recorder.RecordRetry("http://api.example.com/users", 2, false)
		recorder.RecordRetry("http://api.example.com/users", 3, true)
		recorder.RecordRetry("http://api.example.com/posts", 1, true)
	})
}

func TestMetricsRecorder_E2E_ConcurrentUsage(t *testing.T) {
	recorder := NewPrometheusRecorder()
	require.NotNil(t, recorder)

	numGoroutines := 100
	var wg sync.WaitGroup
	wg.Add(numGoroutines)

	for i := 0; i < numGoroutines; i++ {
		go func(id int) {
			defer wg.Done()

			endpoint := fmt.Sprintf("api/endpoint_%d", id%10)
			statusCode := 200
			if id%5 == 0 {
				statusCode = 500
			}

			recorder.RecordRequest(endpoint, statusCode, time.Duration(id)*time.Millisecond)

			url := fmt.Sprintf("http://example.com/api_%d", id%5)
			success := id%3 == 0
			recorder.RecordRetry(url, id, success)
		}(i)
	}

	wg.Wait()
}

func TestMetricsRecorder_E2E_HighVolumeRequests(t *testing.T) {
	recorder := NewPrometheusRecorder()
	require.NotNil(t, recorder)

	endpoints := []string{
		"api/users",
		"api/posts",
		"api/comments",
		"api/likes",
		"api/shares",
	}

	statusCodes := []int{200, 201, 400, 404, 500, 502, 503}

	for i := 0; i < 1000; i++ {
		endpoint := endpoints[i%len(endpoints)]
		statusCode := statusCodes[i%len(statusCodes)]
		duration := time.Duration(i%500) * time.Millisecond

		recorder.RecordRequest(endpoint, statusCode, duration)
	}
}

func TestMetricsRecorder_E2E_RetryScenarios(t *testing.T) {
	recorder := NewPrometheusRecorder()
	require.NotNil(t, recorder)

	t.Run("successful retry after failures", func(t *testing.T) {
		url := "http://api.example.com/flaky-endpoint"
		recorder.RecordRetry(url, 1, false)
		recorder.RecordRetry(url, 2, false)
		recorder.RecordRetry(url, 3, true)
	})

	t.Run("all retries failed", func(t *testing.T) {
		url := "http://api.example.com/down-endpoint"
		recorder.RecordRetry(url, 1, false)
		recorder.RecordRetry(url, 2, false)
		recorder.RecordRetry(url, 3, false)
	})

	t.Run("immediate success no retry needed", func(t *testing.T) {
		url := "http://api.example.com/healthy-endpoint"
		recorder.RecordRetry(url, 1, true)
	})
}

func TestMetricsRecorder_E2E_MixedOperations(t *testing.T) {
	recorder := NewPrometheusRecorder()
	require.NotNil(t, recorder)

	var wg sync.WaitGroup
	numWorkers := 50
	wg.Add(numWorkers)

	for i := 0; i < numWorkers; i++ {
		go func(workerID int) {
			defer wg.Done()

			for j := 0; j < 10; j++ {
				endpoint := fmt.Sprintf("api/worker_%d/operation_%d", workerID, j)
				statusCode := 200
				if j%3 == 0 {
					statusCode = 500
				}
				recorder.RecordRequest(endpoint, statusCode, time.Duration(j*10)*time.Millisecond)

				if j%2 == 0 {
					url := fmt.Sprintf("http://api.example.com/worker_%d", workerID)
					recorder.RecordRetry(url, j, j%4 == 0)
				}
			}
		}(i)
	}

	wg.Wait()
}

func TestMetricsRecorder_E2E_EdgeCases(t *testing.T) {
	recorder := NewPrometheusRecorder()
	require.NotNil(t, recorder)

	t.Run("zero duration request", func(t *testing.T) {
		recorder.RecordRequest("api/fast", 200, 0)
	})

	t.Run("very long duration request", func(t *testing.T) {
		recorder.RecordRequest("api/slow", 200, 10*time.Second)
	})

	t.Run("various status codes", func(t *testing.T) {
		statusCodes := []int{100, 200, 201, 204, 301, 302, 400, 401, 403, 404, 500, 502, 503, 504}
		for _, code := range statusCodes {
			recorder.RecordRequest("api/test", code, 100*time.Millisecond)
		}
	})

	t.Run("empty endpoint name", func(t *testing.T) {
		recorder.RecordRequest("", 200, 100*time.Millisecond)
	})

	t.Run("empty url for retry", func(t *testing.T) {
		recorder.RecordRetry("", 1, true)
	})

	t.Run("high attempt number", func(t *testing.T) {
		recorder.RecordRetry("http://api.example.com/retry", 999, false)
	})
}

func TestMetricsRecorder_E2E_RealWorldScenario(t *testing.T) {
	recorder := NewPrometheusRecorder()
	require.NotNil(t, recorder)

	t.Run("simulate API gateway traffic", func(t *testing.T) {
		var wg sync.WaitGroup

		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := 0; i < 100; i++ {
				recorder.RecordRequest("api/users/list", 200, time.Duration(50+i)*time.Millisecond)
			}
		}()

		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := 0; i < 50; i++ {
				recorder.RecordRequest("api/users/create", 201, time.Duration(100+i)*time.Millisecond)
			}
		}()

		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := 0; i < 30; i++ {
				recorder.RecordRequest("api/users/delete", 204, time.Duration(80+i)*time.Millisecond)
			}
		}()

		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := 0; i < 20; i++ {
				recorder.RecordRequest("api/users/invalid", 404, time.Duration(20+i)*time.Millisecond)
			}
		}()

		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := 0; i < 10; i++ {
				recorder.RecordRequest("api/users/error", 500, time.Duration(200+i)*time.Millisecond)
				recorder.RecordRetry("http://backend.example.com/users", i+1, i >= 8)
			}
		}()

		wg.Wait()
	})
}

func TestMetricsRecorder_E2E_MultipleRecorders(t *testing.T) {
	recorder1 := NewPrometheusRecorder()
	recorder2 := NewPrometheusRecorder()
	recorder3 := NewPrometheusRecorder()

	require.NotNil(t, recorder1)
	require.NotNil(t, recorder2)
	require.NotNil(t, recorder3)

	var wg sync.WaitGroup
	wg.Add(3)

	go func() {
		defer wg.Done()
		for i := 0; i < 50; i++ {
			recorder1.RecordRequest("recorder1/endpoint", 200, time.Duration(i)*time.Millisecond)
		}
	}()

	go func() {
		defer wg.Done()
		for i := 0; i < 50; i++ {
			recorder2.RecordRequest("recorder2/endpoint", 200, time.Duration(i)*time.Millisecond)
		}
	}()

	go func() {
		defer wg.Done()
		for i := 0; i < 50; i++ {
			recorder3.RecordRequest("recorder3/endpoint", 200, time.Duration(i)*time.Millisecond)
		}
	}()

	wg.Wait()
}

func BenchmarkMetricsRecorder_RecordRequest(b *testing.B) {
	recorder := NewPrometheusRecorder()
	b.ResetTimer()

	for i := 0; i < b.N; i++ {
		recorder.RecordRequest("api/benchmark", 200, 100*time.Millisecond)
	}
}

func BenchmarkMetricsRecorder_RecordRetry(b *testing.B) {
	recorder := NewPrometheusRecorder()
	b.ResetTimer()

	for i := 0; i < b.N; i++ {
		recorder.RecordRetry("http://api.example.com/benchmark", i, i%2 == 0)
	}
}

func BenchmarkMetricsRecorder_Concurrent(b *testing.B) {
	recorder := NewPrometheusRecorder()
	b.ResetTimer()

	b.RunParallel(func(pb *testing.PB) {
		i := 0
		for pb.Next() {
			recorder.RecordRequest("api/concurrent", 200, time.Duration(i)*time.Millisecond)
			recorder.RecordRetry("http://api.example.com/concurrent", i, i%2 == 0)
			i++
		}
	})
}

func TestGoCollectorRegistrationError(t *testing.T) {
	// This test verifies that initializeMetrics handles Go collector registration errors gracefully

	// Save original logger
	oldLogger := slog.Default()
	defer slog.SetDefault(oldLogger)

	// Set up test logger to capture warnings
	var logBuf bytes.Buffer
	handler := slog.NewTextHandler(&logBuf, &slog.HandlerOptions{
		Level: slog.LevelWarn,
	})
	testLogger := slog.New(handler)
	slog.SetDefault(testLogger)

	// Save original state
	oldReg := reg
	oldRequestDuration := requestDuration
	oldRetryCounter := retryCounter

	// Reset package state after test
	defer func() {
		reg = oldReg
		requestDuration = oldRequestDuration
		retryCounter = oldRetryCounter
	}()

	// Pre-create a registry with Go collector already registered
	// This will cause initializeMetrics to fail when trying to register it again
	testReg := prometheus.NewRegistry()
	err := testReg.Register(prometheus.NewGoCollector())
	require.NoError(t, err)

	// Set the global registry to our pre-configured one
	reg = testReg
	requestDuration = nil
	retryCounter = nil

	// Call initializeMetrics which will try to register Go collector again and fail
	initializeMetrics()

	// Verify metrics were still created despite the error
	require.NotNil(t, requestDuration)
	require.NotNil(t, retryCounter)

	// Verify the error was logged
	logOutput := logBuf.String()
	assert.Contains(t, logOutput, "Failed to register Prometheus Go collector")
}
