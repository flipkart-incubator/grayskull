package main

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"sync"
	"sync/atomic"
	"time"

	clientapi "github.com/flipkart-incubator/grayskull/clients/go/client-api"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/auth"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/metrics"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/models"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

type testCase struct {
	secretRef   string
	description string
	expectError bool
}

func main() {
	fmt.Println("╔════════════════════════════════════════════════════════════════╗")
	fmt.Println("║         Grayskull Go SDK Comprehensive Test Suite             ║")
	fmt.Println("╚════════════════════════════════════════════════════════════════╝")
	fmt.Println()

	// Create a custom Prometheus registry for metrics
	registry := prometheus.NewRegistry()
	metricsRecorder := metrics.NewPrometheusRecorder(registry)

	// Start metrics server
	go func() {
		http.Handle("/metrics", promhttp.HandlerFor(registry, promhttp.HandlerOpts{}))
		log.Println("📊 Metrics server started at http://localhost:9090/metrics")
		if err := http.ListenAndServe(":9090", nil); err != nil {
			log.Printf("Metrics server error: %v", err)
		}
	}()

	// Give metrics server time to start
	time.Sleep(100 * time.Millisecond)

	// Create basic auth provider
	authProvider, err := auth.NewBasicAuthHeaderProvider("test-user", "test-password")
	if err != nil {
		log.Fatalf("Failed to create auth provider: %v", err)
	}

	// Create client configuration with retry enabled
	config := models.NewDefaultConfig()
	config.Host = "http://localhost:8080"
	config.MaxRetries = 3
	config.MinRetryDelay = 100
	config.MetricsEnabled = true
	config.ReadTimeout = 5000 // 5 seconds

	// Create Grayskull client with metrics
	client, err := client_impl.NewGrayskullClient(authProvider, config, metricsRecorder)
	if err != nil {
		log.Fatalf("Failed to create Grayskull client: %v", err)
	}

	fmt.Println("✓ Client initialized with metrics and retry enabled")
	fmt.Printf("  - Max Retries: %d\n", config.MaxRetries)
	fmt.Printf("  - Min Retry Delay: %dms\n", config.MinRetryDelay)
	fmt.Printf("  - Read Timeout: %dms\n", config.ReadTimeout)
	fmt.Println()

	// Run test suites
	runBasicTests(client)
	runRetryTests(client)
	runEdgeCaseTests(client)
	runConcurrentTests(client)
	runStressTests(client)

	fmt.Println("\n" + "═══════════════════════════════════════════════════════════════")
	fmt.Println("📊 METRICS SUMMARY")
	fmt.Println("═══════════════════════════════════════════════════════════════")
	fmt.Println("Metrics are being collected and exposed at:")
	fmt.Println("  → http://localhost:9090/metrics")
	fmt.Println("\nYou can view Prometheus metrics including:")
	fmt.Println("  • grayskull_http_client_request_duration_seconds")
	fmt.Println("  • grayskull_http_client_retry_attempts_total")
	fmt.Println("\nPress Ctrl+C to exit and stop the metrics server")

	// Keep the program running to allow metrics inspection
	select {}
}

func runBasicTests(client clientapi.Client) {
	fmt.Println("═══════════════════════════════════════════════════════════════")
	fmt.Println("TEST SUITE 1: Basic Secret Retrieval")
	fmt.Println("═══════════════════════════════════════════════════════════════")

	testCases := []testCase{
		{"project1:db-password", "Database password", false},
		{"project1:api-key", "API key", false},
		{"project2:jwt-secret", "JWT secret", false},
	}

	runTests(client, testCases)
}

func runRetryTests(client clientapi.Client) {
	fmt.Println("\n═══════════════════════════════════════════════════════════════")
	fmt.Println("TEST SUITE 2: Retry Logic & Resilience")
	fmt.Println("═══════════════════════════════════════════════════════════════")

	testCases := []testCase{
		{"retry-test:flaky-secret", "Flaky endpoint (succeeds after 2 retries)", false},
		{"retry-test:rate-limited", "Rate limited endpoint (429 → success)", false},
		{"retry-test:server-error", "Persistent server error (exhausts retries)", true},
		{"retry-test:slow-response", "Slow response (tests timeout handling)", false},
	}

	runTests(client, testCases)
}

func runEdgeCaseTests(client clientapi.Client) {
	fmt.Println("\n═══════════════════════════════════════════════════════════════")
	fmt.Println("TEST SUITE 3: Edge Cases & Error Handling")
	fmt.Println("═══════════════════════════════════════════════════════════════")

	testCases := []testCase{
		{"project1:non-existent", "Non-existent secret", true},
		{"project-unknown:secret", "Unknown project", true},
		{"invalid-format", "Invalid secretRef format", true},
		{"", "Empty secretRef", true},
		{":secret", "Missing project ID", true},
		{"project:", "Missing secret name", true},
	}

	runTests(client, testCases)
}

func runTests(client clientapi.Client, testCases []testCase) {
	for i, tc := range testCases {
		fmt.Printf("\n%d. %s\n", i+1, tc.description)
		fmt.Printf("   Secret Ref: %s\n", tc.secretRef)
		fmt.Println("   " + "───────────────────────────────────────────────────────────")

		start := time.Now()
		secret, err := client.GetSecret(context.Background(), tc.secretRef)
		duration := time.Since(start)

		if err != nil {
			if tc.expectError {
				fmt.Printf("   ✅ Expected error received (took %v)\n", duration)
				fmt.Printf("   Error: %v\n", err)
			} else {
				fmt.Printf("   ❌ Unexpected error (took %v)\n", duration)
				fmt.Printf("   Error: %v\n", err)
			}
		} else {
			if tc.expectError {
				fmt.Printf("   ❌ Expected error but got success (took %v)\n", duration)
			} else {
				fmt.Printf("   ✅ Success! (took %v)\n", duration)
				fmt.Printf("   Data Version: %d\n", secret.DataVersion)
				fmt.Printf("   Public Part:  %s\n", secret.PublicPart)
				if secret.PrivatePart != "" {
					fmt.Printf("   Private Part: %s\n", secret.PrivatePart)
				}
			}
		}
	}
}

func runConcurrentTests(client clientapi.Client) {
	fmt.Println("\n═══════════════════════════════════════════════════════════════")
	fmt.Println("TEST SUITE 4: Concurrent Request Handling")
	fmt.Println("═══════════════════════════════════════════════════════════════")

	fmt.Println("\n1. Multiple concurrent successful requests")
	fmt.Println("   " + "───────────────────────────────────────────────────────────")
	
	concurrentRequests := 10
	var wg sync.WaitGroup
	successCount := int32(0)
	errorCount := int32(0)
	
	startTime := time.Now()
	
	for i := 0; i < concurrentRequests; i++ {
		wg.Add(1)
		go func(index int) {
			defer wg.Done()
			_, err := client.GetSecret(context.Background(), "project1:db-password")
			if err != nil {
				atomic.AddInt32(&errorCount, 1)
			} else {
				atomic.AddInt32(&successCount, 1)
			}
		}(i)
	}
	
	wg.Wait()
	duration := time.Since(startTime)
	
	fmt.Printf("   ✅ Completed %d concurrent requests in %v\n", concurrentRequests, duration)
	fmt.Printf("   Success: %d, Errors: %d\n", successCount, errorCount)
	fmt.Printf("   Average time per request: %v\n", duration/time.Duration(concurrentRequests))

	fmt.Println("\n2. Concurrent requests with mixed results")
	fmt.Println("   " + "───────────────────────────────────────────────────────────")
	
	testRefs := []string{
		"project1:db-password",
		"project1:api-key",
		"project2:jwt-secret",
		"project1:non-existent",
		"invalid-format",
	}
	
	successCount = 0
	errorCount = 0
	
	startTime = time.Now()
	
	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func(index int) {
			defer wg.Done()
			ref := testRefs[index%len(testRefs)]
			_, err := client.GetSecret(context.Background(), ref)
			if err != nil {
				atomic.AddInt32(&errorCount, 1)
			} else {
				atomic.AddInt32(&successCount, 1)
			}
		}(i)
	}
	
	wg.Wait()
	duration = time.Since(startTime)
	
	fmt.Printf("   ✅ Completed 20 mixed concurrent requests in %v\n", duration)
	fmt.Printf("   Success: %d, Errors: %d\n", successCount, errorCount)

	fmt.Println("\n3. Concurrent requests with context cancellation")
	fmt.Println("   " + "───────────────────────────────────────────────────────────")
	
	canceledCount := int32(0)
	completedCount := int32(0)
	
	for i := 0; i < 5; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
			defer cancel()
			
			_, err := client.GetSecret(ctx, "retry-test:slow-response")
			if err != nil {
				if ctx.Err() != nil {
					atomic.AddInt32(&canceledCount, 1)
				}
			} else {
				atomic.AddInt32(&completedCount, 1)
			}
		}()
	}
	
	wg.Wait()
	
	fmt.Printf("   ✅ Context cancellation test completed\n")
	fmt.Printf("   Canceled: %d, Completed: %d\n", canceledCount, completedCount)
}

func runStressTests(client clientapi.Client) {
	fmt.Println("\n═══════════════════════════════════════════════════════════════")
	fmt.Println("TEST SUITE 5: Stress & Performance Tests")
	fmt.Println("═══════════════════════════════════════════════════════════════")

	fmt.Println("\n1. High volume request test (100 requests)")
	fmt.Println("   " + "───────────────────────────────────────────────────────────")
	
	totalRequests := 100
	var wg sync.WaitGroup
	successCount := int32(0)
	errorCount := int32(0)
	
	startTime := time.Now()
	
	for i := 0; i < totalRequests; i++ {
		wg.Add(1)
		go func(index int) {
			defer wg.Done()
			secretRef := "project1:db-password"
			if index%3 == 0 {
				secretRef = "project1:api-key"
			} else if index%3 == 1 {
				secretRef = "project2:jwt-secret"
			}
			
			_, err := client.GetSecret(context.Background(), secretRef)
			if err != nil {
				atomic.AddInt32(&errorCount, 1)
			} else {
				atomic.AddInt32(&successCount, 1)
			}
		}(i)
	}
	
	wg.Wait()
	duration := time.Since(startTime)
	
	fmt.Printf("   ✅ Completed %d requests in %v\n", totalRequests, duration)
	fmt.Printf("   Success: %d, Errors: %d\n", successCount, errorCount)
	fmt.Printf("   Throughput: %.2f requests/second\n", float64(totalRequests)/duration.Seconds())
	fmt.Printf("   Average latency: %v\n", duration/time.Duration(totalRequests))

	fmt.Println("\n2. Burst request test (50 requests in rapid succession)")
	fmt.Println("   " + "───────────────────────────────────────────────────────────")
	
	burstSize := 50
	successCount = 0
	errorCount = 0
	
	startTime = time.Now()
	
	for i := 0; i < burstSize; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, err := client.GetSecret(context.Background(), "project1:db-password")
			if err != nil {
				atomic.AddInt32(&errorCount, 1)
			} else {
				atomic.AddInt32(&successCount, 1)
			}
		}()
	}
	
	wg.Wait()
	duration = time.Since(startTime)
	
	fmt.Printf("   ✅ Burst test completed in %v\n", duration)
	fmt.Printf("   Success: %d, Errors: %d\n", successCount, errorCount)
	fmt.Printf("   Peak throughput: %.2f requests/second\n", float64(burstSize)/duration.Seconds())

	fmt.Println("\n3. Sustained load test (30 requests over 3 seconds)")
	fmt.Println("   " + "───────────────────────────────────────────────────────────")
	
	sustainedRequests := 30
	sustainedDuration := 3 * time.Second
	successCount = 0
	errorCount = 0
	
	startTime = time.Now()
	ticker := time.NewTicker(sustainedDuration / time.Duration(sustainedRequests))
	defer ticker.Stop()
	
	for i := 0; i < sustainedRequests; i++ {
		<-ticker.C
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, err := client.GetSecret(context.Background(), "project1:api-key")
			if err != nil {
				atomic.AddInt32(&errorCount, 1)
			} else {
				atomic.AddInt32(&successCount, 1)
			}
		}()
	}
	
	wg.Wait()
	actualDuration := time.Since(startTime)
	
	fmt.Printf("   ✅ Sustained load test completed in %v\n", actualDuration)
	fmt.Printf("   Success: %d, Errors: %d\n", successCount, errorCount)
	fmt.Printf("   Average throughput: %.2f requests/second\n", float64(sustainedRequests)/actualDuration.Seconds())
}
