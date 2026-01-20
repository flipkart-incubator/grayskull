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
			result, err := client.GetSecret(context.Background(), tt.secretRef)

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
				_, err := client.GetSecret(context.Background(), tt.secretRef)
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

func TestRegisterRefreshHook(t *testing.T) {
	mockAuth := &MockAuthProvider{}
	mockHTTPClient := &MockGrayskullHTTPClient{}

	config := &models.GrayskullClientConfiguration{
		Host: "http://localhost:8080",
	}

	client := &client_impl.GrayskullClientImpl{
		AuthHeaderProvider:    mockAuth,
		GrayskullClientConfig: config,
		HttpClient:            mockHTTPClient,
		MetricsRecorder:       metrics.NewPrometheusRecorder(),
	}

	t.Run("successful hook registration", func(t *testing.T) {
		mockHook := &MockSecretRefreshHook{}
		ref, err := client.RegisterRefreshHook(context.Background(), "project:secret", mockHook)

		assert.NoError(t, err)
		assert.NotNil(t, ref)
	})

	t.Run("empty secret ref", func(t *testing.T) {
		mockHook := &MockSecretRefreshHook{}
		ref, err := client.RegisterRefreshHook(context.Background(), "", mockHook)

		assert.Error(t, err)
		assert.Nil(t, ref)
		assert.Contains(t, err.Error(), "secretRef cannot be empty")
	})

	t.Run("nil hook", func(t *testing.T) {
		ref, err := client.RegisterRefreshHook(context.Background(), "project:secret", nil)

		assert.Error(t, err)
		assert.Nil(t, ref)
		assert.Contains(t, err.Error(), "hook cannot be nil")
	})
}

func TestClose(t *testing.T) {
	t.Run("successful close", func(t *testing.T) {
		mockAuth := &MockAuthProvider{}
		mockHTTPClient := &MockGrayskullHTTPClient{}
		mockHTTPClient.On("Close").Return(nil)

		config := &models.GrayskullClientConfiguration{
			Host: "http://localhost:8080",
		}

		client := &client_impl.GrayskullClientImpl{
			AuthHeaderProvider:    mockAuth,
			GrayskullClientConfig: config,
			HttpClient:            mockHTTPClient,
			MetricsRecorder:       metrics.NewPrometheusRecorder(),
		}

		err := client.Close()

		assert.NoError(t, err)
		mockHTTPClient.AssertExpectations(t)
	})

	t.Run("close with error", func(t *testing.T) {
		mockAuth := &MockAuthProvider{}
		mockHTTPClient := &MockGrayskullHTTPClient{}
		mockHTTPClient.On("Close").Return(errors.New("close error"))

		config := &models.GrayskullClientConfiguration{
			Host: "http://localhost:8080",
		}

		client := &client_impl.GrayskullClientImpl{
			AuthHeaderProvider:    mockAuth,
			GrayskullClientConfig: config,
			HttpClient:            mockHTTPClient,
			MetricsRecorder:       metrics.NewPrometheusRecorder(),
		}

		err := client.Close()

		assert.Error(t, err)
		assert.Contains(t, err.Error(), "close error")
		mockHTTPClient.AssertExpectations(t)
	})

	t.Run("close with nil http client", func(t *testing.T) {
		mockAuth := &MockAuthProvider{}
		config := &models.GrayskullClientConfiguration{
			Host: "http://localhost:8080",
		}

		client := &client_impl.GrayskullClientImpl{
			AuthHeaderProvider:    mockAuth,
			GrayskullClientConfig: config,
			HttpClient:            nil,
			MetricsRecorder:       metrics.NewPrometheusRecorder(),
		}

		err := client.Close()

		assert.NoError(t, err)
	})
}

func TestGetSecret_AdditionalScenarios(t *testing.T) {
	mockAuth := &MockAuthProvider{}
	mockAuth.On("GetAuthHeader").Return("Bearer test-token", nil)

	config := &models.GrayskullClientConfiguration{
		Host: "http://localhost:8080",
	}

	t.Run("nil context creates new context with request ID", func(t *testing.T) {
		secretValue := Client_API.SecretValue{
			DataVersion: 1,
			PublicPart:  "test-data",
		}
		resp := response.Response[Client_API.SecretValue]{
			Data:    secretValue,
			Message: "success",
		}
		jsonData, _ := json.Marshal(resp)

		mockHTTPClient := &MockGrayskullHTTPClient{}
		mockHTTPClient.On("DoGetWithRetry", mock.Anything, mock.Anything).
			Return(&response.HttpResponse{
				StatusCode: 200,
				Body:       string(jsonData),
			}, nil)

		client := &client_impl.GrayskullClientImpl{
			BaseURL:               "http://localhost:8080",
			AuthHeaderProvider:    mockAuth,
			GrayskullClientConfig: config,
			HttpClient:            mockHTTPClient,
			MetricsRecorder:       metrics.NewPrometheusRecorder(),
		}

		result, err := client.GetSecret(nil, "project:secret")

		assert.NoError(t, err)
		assert.NotNil(t, result)
		assert.Equal(t, secretValue.PublicPart, result.PublicPart)
	})

	t.Run("HTTP error with nil response", func(t *testing.T) {
		mockHTTPClient := &MockGrayskullHTTPClient{}
		mockHTTPClient.On("DoGetWithRetry", mock.Anything, mock.Anything).
			Return(nil, errors.New("network error"))

		client := &client_impl.GrayskullClientImpl{
			AuthHeaderProvider:    mockAuth,
			GrayskullClientConfig: config,
			HttpClient:            mockHTTPClient,
			MetricsRecorder:       metrics.NewPrometheusRecorder(),
		}

		result, err := client.GetSecret(context.Background(), "project:secret")

		assert.Error(t, err)
		assert.Nil(t, result)
		assert.Contains(t, err.Error(), "failed to fetch secret")
	})

	t.Run("HTTP error with response", func(t *testing.T) {
		mockHTTPClient := &MockGrayskullHTTPClient{}
		mockHTTPClient.On("DoGetWithRetry", mock.Anything, mock.Anything).
			Return(&response.HttpResponse{
				StatusCode: 500,
				Body:       "Internal Server Error",
			}, errors.New("server error"))

		client := &client_impl.GrayskullClientImpl{
			AuthHeaderProvider:    mockAuth,
			GrayskullClientConfig: config,
			HttpClient:            mockHTTPClient,
			MetricsRecorder:       metrics.NewPrometheusRecorder(),
		}

		result, err := client.GetSecret(context.Background(), "project:secret")

		assert.Error(t, err)
		assert.Nil(t, result)
	})

	t.Run("invalid JSON response", func(t *testing.T) {
		mockHTTPClient := &MockGrayskullHTTPClient{}
		mockHTTPClient.On("DoGetWithRetry", mock.Anything, mock.Anything).
			Return(&response.HttpResponse{
				StatusCode: 200,
				Body:       "invalid json",
			}, nil)

		client := &client_impl.GrayskullClientImpl{
			AuthHeaderProvider:    mockAuth,
			GrayskullClientConfig: config,
			HttpClient:            mockHTTPClient,
			MetricsRecorder:       metrics.NewPrometheusRecorder(),
		}

		result, err := client.GetSecret(context.Background(), "project:secret")

		assert.Error(t, err)
		assert.Nil(t, result)
		assert.Contains(t, err.Error(), "failed to parse response")
	})

	t.Run("empty data in response", func(t *testing.T) {
		resp := response.Response[Client_API.SecretValue]{
			Data:    Client_API.SecretValue{},
			Message: "success",
		}
		jsonData, _ := json.Marshal(resp)

		mockHTTPClient := &MockGrayskullHTTPClient{}
		mockHTTPClient.On("DoGetWithRetry", mock.Anything, mock.Anything).
			Return(&response.HttpResponse{
				StatusCode: 200,
				Body:       string(jsonData),
			}, nil)

		client := &client_impl.GrayskullClientImpl{
			AuthHeaderProvider:    mockAuth,
			GrayskullClientConfig: config,
			HttpClient:            mockHTTPClient,
			MetricsRecorder:       metrics.NewPrometheusRecorder(),
		}

		result, err := client.GetSecret(context.Background(), "project:secret")

		assert.Error(t, err)
		assert.Nil(t, result)
		assert.Contains(t, err.Error(), "no data in response")
	})

	t.Run("empty project ID", func(t *testing.T) {
		mockHTTPClient := &MockGrayskullHTTPClient{}

		client := &client_impl.GrayskullClientImpl{
			AuthHeaderProvider:    mockAuth,
			GrayskullClientConfig: config,
			HttpClient:            mockHTTPClient,
			MetricsRecorder:       metrics.NewPrometheusRecorder(),
		}

		result, err := client.GetSecret(context.Background(), ":secret")

		assert.Error(t, err)
		assert.Nil(t, result)
		assert.Contains(t, err.Error(), "projectId and secretName cannot be empty")
	})

	t.Run("empty secret name", func(t *testing.T) {
		mockHTTPClient := &MockGrayskullHTTPClient{}

		client := &client_impl.GrayskullClientImpl{
			AuthHeaderProvider:    mockAuth,
			GrayskullClientConfig: config,
			HttpClient:            mockHTTPClient,
			MetricsRecorder:       metrics.NewPrometheusRecorder(),
		}

		result, err := client.GetSecret(context.Background(), "project:")

		assert.Error(t, err)
		assert.Nil(t, result)
		assert.Contains(t, err.Error(), "projectId and secretName cannot be empty")
	})

	t.Run("URL encoding special characters", func(t *testing.T) {
		secretValue := Client_API.SecretValue{
			DataVersion: 1,
			PublicPart:  "encoded-data",
		}
		resp := response.Response[Client_API.SecretValue]{
			Data: secretValue,
		}
		jsonData, _ := json.Marshal(resp)

		mockHTTPClient := &MockGrayskullHTTPClient{}
		mockHTTPClient.On("DoGetWithRetry", mock.Anything, mock.MatchedBy(func(url string) bool {
			// Verify URL encoding
			return assert.Contains(t, url, "test%2Fproject") && assert.Contains(t, url, "secret%2Fname")
		})).Return(&response.HttpResponse{
			StatusCode: 200,
			Body:       string(jsonData),
		}, nil)

		client := &client_impl.GrayskullClientImpl{
			BaseURL:               "http://localhost:8080",
			AuthHeaderProvider:    mockAuth,
			GrayskullClientConfig: config,
			HttpClient:            mockHTTPClient,
			MetricsRecorder:       metrics.NewPrometheusRecorder(),
		}

		result, err := client.GetSecret(context.Background(), "test/project:secret/name")

		assert.NoError(t, err)
		assert.NotNil(t, result)
	})
}

// MockSecretRefreshHook is a mock implementation of SecretRefreshHook
type MockSecretRefreshHook struct {
	mock.Mock
}

func (m *MockSecretRefreshHook) OnRefresh(secretValue *Client_API.SecretValue) {
	m.Called(secretValue)
}

func (m *MockSecretRefreshHook) OnUpdate(secretValue Client_API.SecretValue) error {
	args := m.Called(secretValue)
	return args.Error(0)
}
