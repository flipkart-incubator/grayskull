package metrics

import (
	"context"
	"fmt"
	"net/http"
	"sync"
	"time"

	"github.com/prometheus/client_golang/prometheus/promhttp"
)

// Server handles the metrics HTTP server
type Server struct {
	server *http.Server
	addr   string
	mu     sync.Mutex
}

// NewServer creates a new metrics server
func NewServer(addr string) *Server {
	return &Server{
		addr: addr,
	}
}

// Start starts the metrics HTTP server
func (s *Server) Start() error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.server != nil {
		return fmt.Errorf("metrics server already running")
	}

	mux := http.NewServeMux()
	mux.Handle("/metrics", promhttp.Handler())

	s.server = &http.Server{
		Addr:              s.addr,
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
	}

	go func() {
		if err := s.server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			// TODO: Log error
			fmt.Printf("Error starting metrics server: %v\n", err)
		}
	}()

	return nil
}

// Stop gracefully shuts down the metrics server
func (s *Server) Stop(ctx context.Context) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.server == nil {
		return nil
	}

	if err := s.server.Shutdown(ctx); err != nil {
		return fmt.Errorf("error shutting down metrics server: %w", err)
	}

	s.server = nil
	return nil
}

// Addr returns the address the server is listening on
func (s *Server) Addr() string {
	return s.addr
}
