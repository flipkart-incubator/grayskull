package main

import (
	"fmt"
	"log"
	"os"

	clientapiworkload "github.com/flipkart-incubator/grayskull/clients/go/client-api/workload"
	client_impl "github.com/flipkart-incubator/grayskull/clients/go/client-impl"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/auth"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/models"
)

// Example 1: Default behavior - uses hostname as workload identity
func exampleDefaultHeaders() {
	// Create configuration with defaults
	config := models.NewDefaultConfig()
	config.Host = "https://grayskull.example.com"

	// Create auth provider (your implementation)
	authProvider := &ExampleAuthProvider{token: "your-api-token"}

	// Create client - headers are set automatically
	client, err := client_impl.NewGrayskullClient(authProvider, config, nil)
	if err != nil {
		log.Fatalf("Failed to create client: %v", err)
	}
	defer client.Close()

	fmt.Println("Client created with default headers:")
	fmt.Printf("  User-Agent: grayskull-go/%s\n", client_impl.GetVersion())
	fmt.Printf("  Grayskull-Workload: %s (hostname)\n", config.GetWorkloadIdentityResolver().Resolve())

	// Now use the client
	// secret, _ := client.GetSecret(context.Background(), "my-secret")
}

// Example 2: Custom workload identity - Kubernetes pod name
func exampleKubernetesWorkload() {
	config := models.NewDefaultConfig()
	config.Host = "https://grayskull.example.com"

	// Use Kubernetes pod name from environment
	podName := os.Getenv("POD_NAME")
	if podName == "" {
		podName = os.Getenv("HOSTNAME") // fallback
	}

	// Set custom workload resolver
	k8sResolver := &K8sWorkloadResolver{podName: podName}
	config.SetWorkloadIdentityResolver(k8sResolver)

	authProvider := &ExampleAuthProvider{token: "your-api-token"}
	client, err := client_impl.NewGrayskullClient(authProvider, config, nil)
	if err != nil {
		log.Fatalf("Failed to create client: %v", err)
	}
	defer client.Close()

	fmt.Println("Client created with Kubernetes workload:")
	fmt.Printf("  User-Agent: grayskull-go/%s\n", client_impl.GetVersion())
	fmt.Printf("  Grayskull-Workload: %s (pod name)\n", podName)
}

// Example 3: Service-based workload identity
func exampleServiceWorkload() {
	config := models.NewDefaultConfig()
	config.Host = "https://grayskull.example.com"

	// Use service name + instance ID
	serviceName := "payment-service"
	instanceID := "abc123" // Could be from env, container ID, etc.
	identity := fmt.Sprintf("%s-%s", serviceName, instanceID)

	resolver := &CustomWorkloadResolver{identity: identity}
	config.SetWorkloadIdentityResolver(resolver)

	authProvider := &ExampleAuthProvider{token: "your-api-token"}
	client, err := client_impl.NewGrayskullClient(authProvider, config, nil)
	if err != nil {
		log.Fatalf("Failed to create client: %v", err)
	}
	defer client.Close()

	fmt.Println("Client created with service workload:")
	fmt.Printf("  User-Agent: grayskull-go/%s\n", client_impl.GetVersion())
	fmt.Printf("  Grayskull-Workload: %s\n", identity)
}

// Example workload resolvers

// K8sWorkloadResolver uses Kubernetes pod name
type K8sWorkloadResolver struct {
	podName string
}

func (r *K8sWorkloadResolver) Resolve() string {
	if r.podName == "" {
		return "unknown-pod"
	}
	return r.podName
}

// CustomWorkloadResolver uses a custom identity string
type CustomWorkloadResolver struct {
	identity string
}

func (r *CustomWorkloadResolver) Resolve() string {
	return r.identity
}

// Example auth provider (implement your own)
type ExampleAuthProvider struct {
	token string
}

func (e *ExampleAuthProvider) GetAuthHeader() (string, error) {
	return "Bearer " + e.token, nil
}

// Verify interface implementations
var (
	_ clientapiworkload.WorkloadIdentityResolver = (*K8sWorkloadResolver)(nil)
	_ clientapiworkload.WorkloadIdentityResolver = (*CustomWorkloadResolver)(nil)
	_ auth.GrayskullAuthHeaderProvider           = (*ExampleAuthProvider)(nil)
)

func main() {
	fmt.Println("=== Example 1: Default Headers ===")
	exampleDefaultHeaders()
	fmt.Println()

	fmt.Println("=== Example 2: Kubernetes Workload ===")
	os.Setenv("POD_NAME", "payment-service-7d8f9c-abcd")
	exampleKubernetesWorkload()
	fmt.Println()

	fmt.Println("=== Example 3: Service Workload ===")
	exampleServiceWorkload()
}
