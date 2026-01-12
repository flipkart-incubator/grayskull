package metrics

import (
	"context"
	"fmt"
	"net"
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

	// Try to listen on the address first to catch any binding errors
	listener, err := net.Listen("tcp", s.addr)
	if err != nil {
		return fmt.Errorf("failed to listen on %s: %w", s.addr, err)
	}

	s.server = &http.Server{
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
	}

	// Start the server in a goroutine
	errChan := make(chan error, 1)
	go func() {
		if err := s.server.Serve(listener); err != nil && err != http.ErrServerClosed {
			errChan <- fmt.Errorf("error running metrics server: %w", err)
		}
		close(errChan)
	}()

	// Check if the server started successfully
	select {
	case err := <-errChan:
		s.server = nil
		return fmt.Errorf("failed to start metrics server: %w", err)
	default:
		// Server started successfully
		return nil
	}
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
