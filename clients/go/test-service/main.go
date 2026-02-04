package main

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strings"
	"sync"
	"time"
)

type SecretValue struct {
	DataVersion int    `json:"dataVersion"`
	PublicPart  string `json:"publicPart"`
	PrivatePart string `json:"privatePart,omitempty"`
}

type Response struct {
	Data    SecretValue `json:"data"`
	Message string      `json:"message,omitempty"`
	Success bool        `json:"success"`
}

// Mock secret store
var secretStore = map[string]map[string]SecretValue{
	"project1": {
		"db-password": {
			DataVersion: 1,
			PublicPart:  "postgres://localhost:5432/mydb",
			PrivatePart: "super-secret-password-123",
		},
		"api-key": {
			DataVersion: 2,
			PublicPart:  "prod-api-key",
			PrivatePart: "sk_live_abc123xyz789",
		},
	},
	"project2": {
		"jwt-secret": {
			DataVersion: 1,
			PublicPart:  "HS256",
			PrivatePart: "my-jwt-secret-key-2024",
		},
	},
	"retry-test": {
		"flaky-secret": {
			DataVersion: 1,
			PublicPart:  "flaky-endpoint",
			PrivatePart: "succeeds-after-retries",
		},
		"rate-limited": {
			DataVersion: 1,
			PublicPart:  "rate-limited-endpoint",
			PrivatePart: "too-many-requests",
		},
		"server-error": {
			DataVersion: 1,
			PublicPart:  "server-error-endpoint",
			PrivatePart: "internal-server-error",
		},
		"slow-response": {
			DataVersion: 1,
			PublicPart:  "slow-endpoint",
			PrivatePart: "delayed-response",
		},
	},
}

// Request tracking for simulating failures
type requestTracker struct {
	mu       sync.Mutex
	attempts map[string]int
}

var tracker = &requestTracker{
	attempts: make(map[string]int),
}

func getSecretHandler(w http.ResponseWriter, r *http.Request) {
	// Extract projectID and secretName from URL path
	// Expected format: /v1/projects/{projectID}/secrets/{secretName}/data
	pathParts := strings.Split(strings.Trim(r.URL.Path, "/"), "/")
	
	if len(pathParts) != 6 || pathParts[0] != "v1" || pathParts[1] != "projects" || pathParts[3] != "secrets" || pathParts[5] != "data" {
		w.WriteHeader(http.StatusBadRequest)
		json.NewEncoder(w).Encode(Response{
			Success: false,
			Message: "Invalid URL format. Expected: /v1/projects/{projectID}/secrets/{secretName}/data",
		})
		return
	}

	projectID := pathParts[2]
	secretName := pathParts[4]

	// Check authorization header
	authHeader := r.Header.Get("Authorization")
	if authHeader == "" {
		w.WriteHeader(http.StatusUnauthorized)
		json.NewEncoder(w).Encode(Response{
			Success: false,
			Message: "Missing Authorization header",
		})
		return
	}

	requestID := r.Header.Get("X-Request-Id")
	log.Printf("Request received - ProjectID: %s, SecretName: %s, RequestID: %s", projectID, secretName, requestID)

	// Simulate retry scenarios for specific secrets
	if projectID == "retry-test" {
		key := fmt.Sprintf("%s:%s:%s", projectID, secretName, requestID)
		tracker.mu.Lock()
		tracker.attempts[key]++
		attempt := tracker.attempts[key]
		tracker.mu.Unlock()

		log.Printf("  Retry test - Attempt #%d for %s:%s", attempt, projectID, secretName)

		switch secretName {
		case "flaky-secret":
			// Fail first 2 attempts with 503, succeed on 3rd
			if attempt < 3 {
				w.WriteHeader(http.StatusServiceUnavailable)
				json.NewEncoder(w).Encode(Response{
					Success: false,
					Message: fmt.Sprintf("Service temporarily unavailable (attempt %d)", attempt),
				})
				log.Printf("  → Returning 503 (will succeed on attempt 3)")
				return
			}
			log.Printf("  → Success after %d attempts!", attempt)

		case "rate-limited":
			// Fail first attempt with 429, succeed on 2nd
			if attempt < 2 {
				w.Header().Set("Retry-After", "1")
				w.WriteHeader(http.StatusTooManyRequests)
				json.NewEncoder(w).Encode(Response{
					Success: false,
					Message: "Rate limit exceeded",
				})
				log.Printf("  → Returning 429 (will succeed on attempt 2)")
				return
			}
			log.Printf("  → Success after rate limit!")

		case "server-error":
			// Always fail with 500 to test max retries
			w.WriteHeader(http.StatusInternalServerError)
			json.NewEncoder(w).Encode(Response{
				Success: false,
				Message: fmt.Sprintf("Internal server error (attempt %d)", attempt),
			})
			log.Printf("  → Returning 500 (always fails)")
			return

		case "slow-response":
			// Simulate slow response
			delay := 500 * time.Millisecond
			log.Printf("  → Simulating slow response (%v delay)", delay)
			time.Sleep(delay)
		}
	}

	// Look up the secret
	project, projectExists := secretStore[projectID]
	if !projectExists {
		w.WriteHeader(http.StatusNotFound)
		json.NewEncoder(w).Encode(Response{
			Success: false,
			Message: fmt.Sprintf("Project '%s' not found", projectID),
		})
		return
	}

	secret, secretExists := project[secretName]
	if !secretExists {
		w.WriteHeader(http.StatusNotFound)
		json.NewEncoder(w).Encode(Response{
			Success: false,
			Message: fmt.Sprintf("Secret '%s' not found in project '%s'", secretName, projectID),
		})
		return
	}

	// Return the secret
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(Response{
		Success: true,
		Data:    secret,
		Message: "Secret retrieved successfully",
	})
}

func healthHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(map[string]string{
		"status": "healthy",
	})
}

func main() {
	http.HandleFunc("/v1/projects/", getSecretHandler)
	http.HandleFunc("/health", healthHandler)

	port := "8080"
	log.Printf("Starting Grayskull mock server on port %s", port)
	log.Printf("Available secrets:")
	for projectID, secrets := range secretStore {
		for secretName := range secrets {
			log.Printf("  - %s:%s", projectID, secretName)
		}
	}
	log.Printf("\nServer ready to accept requests at http://localhost:%s", port)

	if err := http.ListenAndServe(":"+port, nil); err != nil {
		log.Fatal(err)
	}
}
