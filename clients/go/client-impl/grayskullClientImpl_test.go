package client_impl_test

import (
	"context"
	"encoding/json"
	"errors"
	client_impl "github.com/flipkart-incubator/grayskull/client-impl"
	"net/http"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"

	Client_API "github.com/flipkart-incubator/grayskull/client-api/models"
	"github.com/flipkart-incubator/grayskull/client-impl/auth"
	"github.com/flipkart-incubator/grayskull/client-impl/metrics"
	"github.com/flipkart-incubator/grayskull/client-impl/models"
	"github.com/flipkart-incubator/grayskull/client-impl/models/exceptions"
	"github.com/flipkart-incubator/grayskull/client-impl/models/response"
)

// setupTestRegistry sets up a new registry for testing
func setupTestRegistry(t *testing.T) {
	// No-op now as metrics handle their own registration
}

// Use the actual interface from the implementation
type GrayskullHTTPClient = client_impl.GrayskullHTTPClientInterface

// MockGrayskullHTTPClient is a mock implementation of the HTTP client
type MockGrayskullHTTPClient struct {
	mock.Mock
}

func (m *MockGrayskullHTTPClient) DoGetWithRetry(ctx context.Context, url string) (*response.HttpResponse, error) {
	args := m.Called(ctx, url)
	if args.Get(0) == nil {
		return nil, args.Error(1)
	}
	return args.Get(0).(*response.HttpResponse), args.Error(1)
}

func (m *MockGrayskullHTTPClient) Close() error {
	args := m.Called()
	return args.Error(0)
}

// MockAuthProvider is a mock implementation of the auth provider
type MockAuthProvider struct {
	mock.Mock
}

func (m *MockAuthProvider) GetAuthHeader() (string, error) {
	args := m.Called()
	return args.String(0), args.Error(1)
}

func TestNewGrayskullClient(t *testing.T) {
	tests := []struct {
		name         string
		authProvider auth.GrayskullAuthHeaderProvider
		config       *models.GrayskullClientConfiguration
		expectError  bool
	}{
		{
			name:         "successful client creation",
			authProvider: &MockAuthProvider{},
			config: &models.GrayskullClientConfiguration{
				Host: "http://localhost:8080",
			},
			expectError: false,
		},
		{
			name:         "nil auth provider",
			authProvider: nil,
			config: &models.GrayskullClientConfiguration{
				Host: "http://localhost:8080",
			},
			expectError: true,
		},
		{
			name:         "nil config",
			authProvider: &MockAuthProvider{},
			config:       nil,
			expectError:  true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			// Create a test config if not provided
			config := tt.config
			if config == nil && !tt.expectError {
				config = &models.GrayskullClientConfiguration{
					Host: "http://test.local",
				}
			}

			client, err := client_impl.NewGrayskullClient(tt.authProvider, config)

			if tt.expectError {
				assert.Error(t, err, "Expected error but got none")
				assert.Nil(t, client, "Expected nil client when error occurs")
			} else {
				assert.NoError(t, err, "Unexpected error")
				assert.NotNil(t, client, "Expected non-nil client")
			}
		})
	}
}

func TestGetSecret(t *testing.T) {
	mockAuth := &MockAuthProvider{}
	mockAuth.On("GetAuthHeader").Return("Bearer test-token", nil)

	config := &models.GrayskullClientConfiguration{
		Host: "http://localhost:8080",
	}

	tests := []struct {
		name           string
		secretRef      string
		setupMock      func(*mock.Mock)
		expectedError  error
		expectedResult *Client_API.SecretValue
	}{
		{
			name:      "successful secret retrieval",
			secretRef: "test-project:test-secret",
			setupMock: func(m *mock.Mock) {
				secretValue := Client_API.SecretValue{
					DataVersion: 1,
					PublicPart:  "public-data",
				}
				resp := response.Response[Client_API.SecretValue]{
					Data: secretValue,
				}
				jsonData, _ := json.Marshal(resp)

				m.On("DoGetWithRetry", mock.Anything, mock.Anything).
					Return(&response.HttpResponse{
						StatusCode: http.StatusOK,
						Body:       string(jsonData),
					}, nil)
			},
			expectedResult: &Client_API.SecretValue{
				DataVersion: 1,
				PublicPart:  "public-data",
			},
		},
		{
			name:          "empty secret ref",
			secretRef:     "",
			setupMock:     func(m *mock.Mock) {},
			expectedError: errors.New("secretRef cannot be empty"),
		},
		{
			name:          "invalid secret ref format",
			secretRef:     "invalid-format",
			setupMock:     func(m *mock.Mock) {},
			expectedError: exceptions.NewGrayskullError(400, "invalid secretRef format. Expected 'projectId:secretName', got: invalid-format"),
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			// Setup mock HTTP client
			mockHTTPClient := &MockGrayskullHTTPClient{}
			if tt.setupMock != nil {
				tt.setupMock(&mockHTTPClient.Mock)
			}

			// Create client with mock HTTP client and metrics recorder
			client := &client_impl.GrayskullClientImpl{
				AuthHeaderProvider:    mockAuth,
				GrayskullClientConfig: config,
				HttpClient:            mockHTTPClient,
				MetricsRecorder:       metrics.NewPrometheusRecorder(),
			}

			// Call the method under test
			result, err := client.GetSecret(tt.secretRef)

			// Assertions
			if tt.expectedError != nil {
				assert.Error(t, err)
				assert.Contains(t, err.Error(), tt.expectedError.Error())
			} else {
				assert.NoError(t, err)
				assert.Equal(t, tt.expectedResult, result)
			}

			// Verify mock expectations
			mockHTTPClient.AssertExpectations(t)
		})
	}
}

func TestSplitSecretRef(t *testing.T) {
	// Setup a mock HTTP client
	mockHTTPClient := &MockGrayskullHTTPClient{}

	// Create a properly initialized client
	client := &client_impl.GrayskullClientImpl{
		BaseURL:    "http://test",
		HttpClient: mockHTTPClient,
	}

	tests := []struct {
		name          string
		secretRef     string
		expectedParts []string
		expectedError bool
		errorContains string
	}{
		{
			name:          "valid secret ref",
			secretRef:     "project1:secret1",
			expectedParts: []string{"project1", "secret1"},
			expectedError: false,
		},
		{
			name:          "empty secret ref",
			secretRef:     "",
			expectedError: true,
			errorContains: "secretRef cannot be empty",
		},
		{
			name:          "invalid format - missing colon",
			secretRef:     "invalid-format",
			expectedError: true,
			errorContains: "invalid secretRef format",
		},
		{
			name:          "secret name with colon",
			secretRef:     "project1:secret:with:colons",
			expectedParts: []string{"project1", "secret:with:colons"},
			expectedError: false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			// Call SplitSecretRef directly
			parts := client.SplitSecretRef(tt.secretRef)

			if tt.expectedError {
				// For error cases, test both SplitSecretRef and GetSecret
				assert.Equal(t, []string{tt.secretRef}, parts) // Split with no colon returns original string in slice

				// Test GetSecret's error handling
				_, err := client.GetSecret(tt.secretRef)
				assert.Error(t, err)
				if tt.errorContains != "" {
					assert.Contains(t, err.Error(), tt.errorContains)
				}
			} else {
				// Verify the parts are split correctly
				assert.Equal(t, tt.expectedParts, parts)

				// For non-error cases, we don't test GetSecret here as it requires
				// more complex mocking of the HTTP client
			}
		})
	}
}

// Add more test functions for other methods as needed
