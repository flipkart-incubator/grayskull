package client_impl

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"sync"
	"testing"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"

	Client_API "github.com/flipkart-incubator/grayskull/clients/go/client-api/models"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/auth"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal"
	internalHooks "github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal/hooks"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal/models/response"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/metrics"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/models"
	grayskullErrors "github.com/flipkart-incubator/grayskull/clients/go/client-impl/models/errors"
)

// setupTestRegistry sets up a new registry for testing
func setupTestRegistry(t *testing.T) {
	// No-op now as metrics handle their own registration
}

// Use the actual interface from the implementation
type GrayskullHTTPClient = internal.GrayskullHTTPClientInterface

// MockGrayskullHTTPClient is a mock implementation of the HTTP client
type MockGrayskullHTTPClient struct {
	mock.Mock
}

func (m *MockGrayskullHTTPClient) DoGetWithRetry(ctx context.Context, url string, result any) (int, error) {
	args := m.Called(ctx, url, result)
	return args.Int(0), args.Error(1)
}

func (m *MockGrayskullHTTPClient) DoPostWithRetry(ctx context.Context, url string, jsonBody []byte, result any) (int, error) {
	args := m.Called(ctx, url, jsonBody, result)
	return args.Int(0), args.Error(1)
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

func NewGrayskullClientForTesting(
	baseURL string,
	authProvider auth.GrayskullAuthHeaderProvider,
	config *models.GrayskullClientConfiguration,
	httpClient internal.GrayskullHTTPClientInterface,
	metricsRecorder metrics.MetricsRecorder,
) *GrayskullClientImpl {
	return &GrayskullClientImpl{
		baseURL:            baseURL,
		authHeaderProvider: authProvider,
		clientConfig:       config,
		httpClient:         httpClient,
		metricsRecorder:    metricsRecorder,
		registry:           internalHooks.NewRegistry(),
	}
}

func testNilContext() context.Context { return nil }

func TestValidateConfig(t *testing.T) {
	tests := []struct {
		name          string
		config        *models.GrayskullClientConfiguration
		expectError   bool
		errorContains string
	}{
		{
			name: "valid config",
			config: &models.GrayskullClientConfiguration{
				Host:           "http://localhost:8080",
				MaxConnections: 10,
			},
			expectError: false,
		},
		{
			name: "missing required Host",
			config: &models.GrayskullClientConfiguration{
				MaxConnections: 10,
			},
			expectError:   true,
			errorContains: "Host is required",
		},
		{
			name: "invalid Host URL",
			config: &models.GrayskullClientConfiguration{
				Host:           "not-a-valid-url",
				MaxConnections: 10,
			},
			expectError:   true,
			errorContains: "invalid host URL",
		},
		{
			name: "negative ConnectionTimeout",
			config: &models.GrayskullClientConfiguration{
				Host:              "http://localhost:8080",
				ConnectionTimeout: -1,
				MaxConnections:    10,
			},
			expectError:   true,
			errorContains: "ConnectionTimeout cannot be negative",
		},
		{
			name: "negative ReadTimeout",
			config: &models.GrayskullClientConfiguration{
				Host:           "http://localhost:8080",
				ReadTimeout:    -1,
				MaxConnections: 10,
			},
			expectError:   true,
			errorContains: "ReadTimeout cannot be negative",
		},
		{
			name: "zero MaxConnections",
			config: &models.GrayskullClientConfiguration{
				Host:           "http://localhost:8080",
				MaxConnections: 0,
			},
			expectError:   true,
			errorContains: "MaxConnections must be greater than 0",
		},
		{
			name: "negative MaxConnections",
			config: &models.GrayskullClientConfiguration{
				Host:           "http://localhost:8080",
				MaxConnections: -1,
			},
			expectError:   true,
			errorContains: "MaxConnections must be greater than 0",
		},
		{
			name: "negative IdleConnTimeout",
			config: &models.GrayskullClientConfiguration{
				Host:            "http://localhost:8080",
				IdleConnTimeout: -1,
				MaxConnections:  10,
			},
			expectError:   true,
			errorContains: "IdleConnTimeout cannot be negative",
		},
		{
			name: "negative MaxIdleConns",
			config: &models.GrayskullClientConfiguration{
				Host:           "http://localhost:8080",
				MaxIdleConns:   -1,
				MaxConnections: 10,
			},
			expectError:   true,
			errorContains: "MaxIdleConns cannot be negative",
		},
		{
			name: "negative MaxIdleConnsPerHost",
			config: &models.GrayskullClientConfiguration{
				Host:                "http://localhost:8080",
				MaxIdleConnsPerHost: -1,
				MaxConnections:      10,
			},
			expectError:   true,
			errorContains: "MaxIdleConnsPerHost cannot be negative",
		},
		{
			name: "negative MaxRetries",
			config: &models.GrayskullClientConfiguration{
				Host:           "http://localhost:8080",
				MaxRetries:     -1,
				MaxConnections: 10,
			},
			expectError:   true,
			errorContains: "MaxRetries cannot be negative",
		},
		{
			name: "negative MinRetryDelay",
			config: &models.GrayskullClientConfiguration{
				Host:           "http://localhost:8080",
				MinRetryDelay:  -1,
				MaxConnections: 10,
			},
			expectError:   true,
			errorContains: "MinRetryDelay cannot be negative",
		},
		{
			name: "valid HTTPS URL",
			config: &models.GrayskullClientConfiguration{
				Host:           "https://grayskull.example.com",
				MaxConnections: 10,
			},
			expectError: false,
		},
		{
			name: "valid URL with port",
			config: &models.GrayskullClientConfiguration{
				Host:           "http://localhost:9090",
				MaxConnections: 5,
			},
			expectError: false,
		},
		{
			name: "all fields valid with zero timeouts",
			config: &models.GrayskullClientConfiguration{
				Host:                "http://localhost:8080",
				ConnectionTimeout:   0,
				ReadTimeout:         0,
				MaxConnections:      1,
				IdleConnTimeout:     0,
				MaxIdleConns:        0,
				MaxIdleConnsPerHost: 0,
				MaxRetries:          0,
				MinRetryDelay:       0,
			},
			expectError: false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := validateConfig(tt.config)

			if tt.expectError {
				assert.Error(t, err)
				if tt.errorContains != "" {
					assert.Contains(t, err.Error(), tt.errorContains)
				}
			} else {
				assert.NoError(t, err)
			}
		})
	}
}

func TestValidateConfig_NilConfigReturnsInvalidConfigurationError(t *testing.T) {
	err := validateConfig(nil)
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "invalid configuration")
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
				Host:           "http://localhost:8080",
				MaxConnections: 10,
			},
			expectError: false,
		},
		{
			name:         "nil auth provider",
			authProvider: nil,
			config: &models.GrayskullClientConfiguration{
				Host:           "http://localhost:8080",
				MaxConnections: 10,
			},
			expectError: true,
		},
		{
			name:         "nil config",
			authProvider: &MockAuthProvider{},
			config:       nil,
			expectError:  true,
		},
		{
			name:         "invalid config - missing host",
			authProvider: &MockAuthProvider{},
			config: &models.GrayskullClientConfiguration{
				MaxConnections: 10,
			},
			expectError: true,
		},
		{
			name:         "invalid config - invalid URL",
			authProvider: &MockAuthProvider{},
			config: &models.GrayskullClientConfiguration{
				Host:           "not-a-url",
				MaxConnections: 10,
			},
			expectError: true,
		},
		{
			name:         "invalid config - zero MaxConnections",
			authProvider: &MockAuthProvider{},
			config: &models.GrayskullClientConfiguration{
				Host:           "http://localhost:8080",
				MaxConnections: 0,
			},
			expectError: true,
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

			client, err := NewGrayskullClient(tt.authProvider, config, metrics.NewPrometheusRecorder(prometheus.NewRegistry()))

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

func TestNewGrayskullClient_WithNilMetricsRecorder_UsesDefault(t *testing.T) {
	mockAuth := &MockAuthProvider{}
	config := &models.GrayskullClientConfiguration{
		Host:           "http://localhost:8080",
		MaxConnections: 10,
	}

	client, err := NewGrayskullClient(mockAuth, config, nil)
	assert.NoError(t, err)
	assert.NotNil(t, client)
	assert.NoError(t, client.Close())
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
				resp := response.NewResponse(secretValue, "")
				jsonData, _ := json.Marshal(resp)

				m.On("DoGetWithRetry", mock.Anything, mock.Anything, mock.Anything).
					Run(func(args mock.Arguments) {
						result := args.Get(2).(*response.Response[Client_API.SecretValue])
						json.Unmarshal(jsonData, result)
					}).
					Return(http.StatusOK, nil)
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
			expectedError: grayskullErrors.NewGrayskullError(400, "invalid secretRef format. Expected 'projectId:secretName', got: invalid-format"),
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
			client := NewGrayskullClientForTesting(
				config.Host,
				mockAuth,
				config,
				mockHTTPClient,
				metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
			)

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

// Note: splitSecretRef is tested indirectly through GetSecret tests
// since it's an unexported method

func TestRegisterRefreshHook(t *testing.T) {
	mockAuth := &MockAuthProvider{}
	mockHTTPClient := &MockGrayskullHTTPClient{}

	config := &models.GrayskullClientConfiguration{
		Host: "http://localhost:8080",
	}

	client := NewGrayskullClientForTesting(
		config.Host,
		mockAuth,
		config,
		mockHTTPClient,
		metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
	)

	t.Run("successful hook registration", func(t *testing.T) {
		hook := func(secret Client_API.SecretValue) error {
			return nil
		}
		ref, err := client.RegisterRefreshHook(context.Background(), "project:secret", hook)

		assert.NoError(t, err)
		assert.NotNil(t, ref)
	})

	t.Run("empty secret ref", func(t *testing.T) {
		hook := func(secret Client_API.SecretValue) error {
			return nil
		}
		ref, err := client.RegisterRefreshHook(context.Background(), "", hook)

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

func TestGetSecret_AdditionalScenarios(t *testing.T) {
	mockAuth := &MockAuthProvider{}
	mockAuth.On("GetAuthHeader").Return("Bearer test-token", nil)

	config := &models.GrayskullClientConfiguration{
		Host: "http://localhost:8080",
	}

	t.Run("context TODO path succeeds", func(t *testing.T) {
		secretValue := Client_API.SecretValue{
			DataVersion: 1,
			PublicPart:  "test-data",
		}
		resp := response.NewResponse(secretValue, "success")
		jsonData, _ := json.Marshal(resp)

		mockHTTPClient := &MockGrayskullHTTPClient{}
		mockHTTPClient.On("DoGetWithRetry", mock.Anything, mock.Anything, mock.Anything).
			Run(func(args mock.Arguments) {
				result := args.Get(2).(*response.Response[Client_API.SecretValue])
				json.Unmarshal(jsonData, result)
			}).
			Return(200, nil)

		client := NewGrayskullClientForTesting(
			"http://localhost:8080",
			mockAuth,
			config,
			mockHTTPClient,
			metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		)

		result, err := client.GetSecret(context.TODO(), "project:secret")

		assert.NoError(t, err)
		assert.NotNil(t, result)
		assert.Equal(t, secretValue.PublicPart, result.PublicPart)
	})

	t.Run("nil context path succeeds", func(t *testing.T) {
		secretValue := Client_API.SecretValue{
			DataVersion: 2,
			PublicPart:  "nil-context-data",
		}
		resp := response.NewResponse(secretValue, "success")
		jsonData, _ := json.Marshal(resp)

		mockHTTPClient := &MockGrayskullHTTPClient{}
		mockHTTPClient.On("DoGetWithRetry", mock.Anything, mock.Anything, mock.Anything).
			Run(func(args mock.Arguments) {
				result := args.Get(2).(*response.Response[Client_API.SecretValue])
				json.Unmarshal(jsonData, result)
			}).
			Return(200, nil)

		client := NewGrayskullClientForTesting(
			"http://localhost:8080",
			mockAuth,
			config,
			mockHTTPClient,
			metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		)

		result, err := client.GetSecret(testNilContext(), "project:secret")

		assert.NoError(t, err)
		assert.NotNil(t, result)
		assert.Equal(t, secretValue.PublicPart, result.PublicPart)
	})

	t.Run("HTTP error with nil response", func(t *testing.T) {
		mockHTTPClient := &MockGrayskullHTTPClient{}
		mockHTTPClient.On("DoGetWithRetry", mock.Anything, mock.Anything, mock.Anything).
			Return(0, errors.New("network error"))

		client := NewGrayskullClientForTesting(
			config.Host,
			mockAuth,
			config,
			mockHTTPClient,
			metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		)

		result, err := client.GetSecret(context.Background(), "project:secret")

		assert.Error(t, err)
		assert.Nil(t, result)
		assert.Contains(t, err.Error(), "failed to fetch secret")
	})

	t.Run("HTTP error with response", func(t *testing.T) {
		mockHTTPClient := &MockGrayskullHTTPClient{}
		mockHTTPClient.On("DoGetWithRetry", mock.Anything, mock.Anything, mock.Anything).
			Return(500, errors.New("server error"))

		client := NewGrayskullClientForTesting(
			config.Host,
			mockAuth,
			config,
			mockHTTPClient,
			metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		)

		result, err := client.GetSecret(context.Background(), "project:secret")

		assert.Error(t, err)
		assert.Nil(t, result)
	})

	t.Run("invalid JSON response", func(t *testing.T) {
		mockHTTPClient := &MockGrayskullHTTPClient{}
		mockHTTPClient.On("DoGetWithRetry", mock.Anything, mock.Anything, mock.Anything).
			Run(func(args mock.Arguments) {
				result := args.Get(2).(*response.Response[Client_API.SecretValue])
				json.Unmarshal([]byte("invalid json"), result)
			}).
			Return(200, errors.New("failed to unmarshal response: invalid character 'i' looking for beginning of value"))

		client := NewGrayskullClientForTesting(
			config.Host,
			mockAuth,
			config,
			mockHTTPClient,
			metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		)

		result, err := client.GetSecret(context.Background(), "project:secret")

		assert.Error(t, err)
		assert.Nil(t, result)
		assert.Contains(t, err.Error(), "failed to unmarshal response")
	})

	t.Run("empty data in response", func(t *testing.T) {
		resp := response.NewResponse(Client_API.SecretValue{}, "success")
		jsonData, _ := json.Marshal(resp)

		mockHTTPClient := &MockGrayskullHTTPClient{}
		mockHTTPClient.On("DoGetWithRetry", mock.Anything, mock.Anything, mock.Anything).
			Run(func(args mock.Arguments) {
				result := args.Get(2).(*response.Response[Client_API.SecretValue])
				json.Unmarshal(jsonData, result)
			}).
			Return(200, nil)

		client := NewGrayskullClientForTesting(
			config.Host,
			mockAuth,
			config,
			mockHTTPClient,
			metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		)

		result, err := client.GetSecret(context.Background(), "project:secret")

		assert.Error(t, err)
		assert.Nil(t, result)
		assert.Contains(t, err.Error(), "no data in response")
	})

	t.Run("empty project ID", func(t *testing.T) {
		mockHTTPClient := &MockGrayskullHTTPClient{}

		client := NewGrayskullClientForTesting(
			config.Host,
			mockAuth,
			config,
			mockHTTPClient,
			metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		)

		result, err := client.GetSecret(context.Background(), ":secret")

		assert.Error(t, err)
		assert.Nil(t, result)
		assert.Contains(t, err.Error(), "projectId and secretName cannot be empty")
	})

	t.Run("empty secret name", func(t *testing.T) {
		mockHTTPClient := &MockGrayskullHTTPClient{}

		client := NewGrayskullClientForTesting(
			config.Host,
			mockAuth,
			config,
			mockHTTPClient,
			metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		)

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
		resp := response.NewResponse(secretValue, "")
		jsonData, _ := json.Marshal(resp)

		mockHTTPClient := &MockGrayskullHTTPClient{}
		mockHTTPClient.On("DoGetWithRetry", mock.Anything, mock.MatchedBy(func(url string) bool {
			// Verify URL encoding
			return assert.Contains(t, url, "test%2Fproject") && assert.Contains(t, url, "secret%2Fname")
		}), mock.Anything).
			Run(func(args mock.Arguments) {
				result := args.Get(2).(*response.Response[Client_API.SecretValue])
				json.Unmarshal(jsonData, result)
			}).
			Return(200, nil)

		client := NewGrayskullClientForTesting(
			"http://localhost:8080",
			mockAuth,
			config,
			mockHTTPClient,
			metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		)

		result, err := client.GetSecret(context.Background(), "test/project:secret/name")

		assert.NoError(t, err)
		assert.NotNil(t, result)
	})
}

// TestRegisterRefreshHook_Success verifies successful hook registration
func TestRegisterRefreshHook_Success(t *testing.T) {
	mockAuth := &MockAuthProvider{}
	mockHTTPClient := &MockGrayskullHTTPClient{}

	config := &models.GrayskullClientConfiguration{
		Host:           "http://localhost:8080",
		MaxConnections: 10,
	}

	registry := internalHooks.NewRegistry()
	client := &GrayskullClientImpl{
		baseURL:            config.Host,
		authHeaderProvider: mockAuth,
		clientConfig:       config,
		httpClient:         mockHTTPClient,
		metricsRecorder:    metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		registry:           registry,
	}

	hook := func(v Client_API.SecretValue) error {
		return nil
	}

	ref, err := client.RegisterRefreshHook(context.Background(), "project:secret", hook)

	assert.NoError(t, err)
	assert.NotNil(t, ref)
	assert.True(t, ref.IsActive())
	assert.Equal(t, "project:secret", ref.GetSecretRef())

	// Verify state was created in registry
	state := registry.Get("project:secret")
	assert.NotNil(t, state)
}

// TestRegisterRefreshHook_CanUnregister verifies that unregistering a hook works
func TestRegisterRefreshHook_CanUnregister(t *testing.T) {
	mockAuth := &MockAuthProvider{}
	mockHTTPClient := &MockGrayskullHTTPClient{}

	config := &models.GrayskullClientConfiguration{
		Host:           "http://localhost:8080",
		MaxConnections: 10,
	}

	registry := internalHooks.NewRegistry()
	client := &GrayskullClientImpl{
		baseURL:            config.Host,
		authHeaderProvider: mockAuth,
		clientConfig:       config,
		httpClient:         mockHTTPClient,
		metricsRecorder:    metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		registry:           registry,
	}

	hook := func(v Client_API.SecretValue) error { return nil }

	ref, err := client.RegisterRefreshHook(context.Background(), "project:secret", hook)
	assert.NoError(t, err)
	assert.True(t, ref.IsActive())

	// Unregister
	ref.Unregister()
	assert.False(t, ref.IsActive())

	// Unregister again should be idempotent
	ref.Unregister()
	assert.False(t, ref.IsActive())
}

// TestRegisterRefreshHook_InvalidSecretRef verifies error for invalid secretRef
func TestRegisterRefreshHook_InvalidSecretRef(t *testing.T) {
	mockAuth := &MockAuthProvider{}
	mockHTTPClient := &MockGrayskullHTTPClient{}

	config := &models.GrayskullClientConfiguration{
		Host:           "http://localhost:8080",
		MaxConnections: 10,
	}

	registry := internalHooks.NewRegistry()
	client := &GrayskullClientImpl{
		baseURL:            config.Host,
		authHeaderProvider: mockAuth,
		clientConfig:       config,
		httpClient:         mockHTTPClient,
		metricsRecorder:    metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		registry:           registry,
	}

	hook := func(v Client_API.SecretValue) error { return nil }

	// No colon
	ref, err := client.RegisterRefreshHook(context.Background(), "no-colon", hook)
	assert.Error(t, err)
	assert.Nil(t, ref)
	assert.Contains(t, err.Error(), "invalid secretRef format")

	// Empty projectID
	ref, err = client.RegisterRefreshHook(context.Background(), ":secret", hook)
	assert.Error(t, err)
	assert.Nil(t, ref)

	// Empty secretName
	ref, err = client.RegisterRefreshHook(context.Background(), "project:", hook)
	assert.Error(t, err)
	assert.Nil(t, ref)
}

// TestGetSecret_ThenRegisterHook_SeedsPollerWithObservedVersion verifies
// that getSecret observed version is used to seed the hook's lastKnownVersion
func TestGetSecret_ThenRegisterHook_SeedsPollerWithObservedVersion(t *testing.T) {
	mockAuth := &MockAuthProvider{}
	mockAuth.On("GetAuthHeader").Return("Bearer test-token", nil)

	mockHTTPClient := &MockGrayskullHTTPClient{}

	secretValue := Client_API.SecretValue{
		DataVersion: 7,
		PublicPart:  "user",
		PrivatePart: "pwd",
	}
	resp := response.NewResponse(secretValue, "Success")
	jsonData, _ := json.Marshal(resp)

	mockHTTPClient.On("DoGetWithRetry", mock.Anything, mock.Anything, mock.Anything).
		Run(func(args mock.Arguments) {
			result := args.Get(2).(*response.Response[Client_API.SecretValue])
			json.Unmarshal(jsonData, result)
		}).
		Return(200, nil)

	config := &models.GrayskullClientConfiguration{
		Host:           "http://localhost:8080",
		MaxConnections: 10,
	}

	registry := internalHooks.NewRegistry()
	client := &GrayskullClientImpl{
		baseURL:            config.Host,
		authHeaderProvider: mockAuth,
		clientConfig:       config,
		httpClient:         mockHTTPClient,
		metricsRecorder:    metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		registry:           registry,
	}

	// GetSecret observes v7
	_, err := client.GetSecret(context.Background(), "team:db-pass")
	assert.NoError(t, err)

	// Register hook
	hook := func(v Client_API.SecretValue) error { return nil }
	ref, err := client.RegisterRefreshHook(context.Background(), "team:db-pass", hook)
	assert.NoError(t, err)
	assert.NotNil(t, ref)

	// Verify lastKnownVersion was seeded with 7
	state := registry.Get("team:db-pass")
	assert.NotNil(t, state)
	assert.Equal(t, int32(7), state.LastKnownVersion.Load())
}

// TestRegisterHook_WithoutPriorGetSecret_StartsFromVersionZero verifies
// that without a prior getSecret, the hook starts with lastKnownVersion=0
func TestRegisterHook_WithoutPriorGetSecret_StartsFromVersionZero(t *testing.T) {
	mockAuth := &MockAuthProvider{}
	mockHTTPClient := &MockGrayskullHTTPClient{}

	config := &models.GrayskullClientConfiguration{
		Host:           "http://localhost:8080",
		MaxConnections: 10,
	}

	registry := internalHooks.NewRegistry()
	client := &GrayskullClientImpl{
		baseURL:            config.Host,
		authHeaderProvider: mockAuth,
		clientConfig:       config,
		httpClient:         mockHTTPClient,
		metricsRecorder:    metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		registry:           registry,
	}

	hook := func(v Client_API.SecretValue) error { return nil }
	ref, err := client.RegisterRefreshHook(context.Background(), "team:no-get-secret", hook)
	assert.NoError(t, err)
	assert.NotNil(t, ref)

	// Verify lastKnownVersion is 0
	state := registry.Get("team:no-get-secret")
	assert.NotNil(t, state)
	assert.Equal(t, int32(0), state.LastKnownVersion.Load())
}

// TestGetSecret_MultipleCallsSameSecret_SeedUsesLastObservedVersion verifies
// that multiple getSecret calls update the seed version, and the last one wins
func TestGetSecret_MultipleCallsSameSecret_SeedUsesLastObservedVersion(t *testing.T) {
	mockAuth := &MockAuthProvider{}
	mockAuth.On("GetAuthHeader").Return("Bearer test-token", nil)

	mockHTTPClient := &MockGrayskullHTTPClient{}

	// First call returns v9
	secretValue1 := Client_API.SecretValue{
		DataVersion: 9,
		PublicPart:  "u",
		PrivatePart: "p",
	}
	resp1 := response.NewResponse(secretValue1, "Success")
	jsonData1, _ := json.Marshal(resp1)

	// Second call returns v11
	secretValue2 := Client_API.SecretValue{
		DataVersion: 11,
		PublicPart:  "u",
		PrivatePart: "p",
	}
	resp2 := response.NewResponse(secretValue2, "Success")
	jsonData2, _ := json.Marshal(resp2)

	callCount := 0
	mockHTTPClient.On("DoGetWithRetry", mock.Anything, mock.Anything, mock.Anything).
		Run(func(args mock.Arguments) {
			result := args.Get(2).(*response.Response[Client_API.SecretValue])
			callCount++
			if callCount == 1 {
				json.Unmarshal(jsonData1, result)
			} else {
				json.Unmarshal(jsonData2, result)
			}
		}).
		Return(200, nil)

	config := &models.GrayskullClientConfiguration{
		Host:           "http://localhost:8080",
		MaxConnections: 10,
	}

	registry := internalHooks.NewRegistry()
	client := &GrayskullClientImpl{
		baseURL:            config.Host,
		authHeaderProvider: mockAuth,
		clientConfig:       config,
		httpClient:         mockHTTPClient,
		metricsRecorder:    metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		registry:           registry,
	}

	// First getSecret observes v9
	_, err := client.GetSecret(context.Background(), "team:rotating")
	assert.NoError(t, err)

	// Second getSecret observes v11 (this is the last call)
	_, err = client.GetSecret(context.Background(), "team:rotating")
	assert.NoError(t, err)

	// Register hook - should seed with v11
	hook := func(v Client_API.SecretValue) error { return nil }
	_, err = client.RegisterRefreshHook(context.Background(), "team:rotating", hook)
	assert.NoError(t, err)

	// Verify lastKnownVersion was seeded with 11 (last observed)
	state := registry.Get("team:rotating")
	assert.NotNil(t, state)
	assert.Equal(t, int32(11), state.LastKnownVersion.Load())
}

// TestRegisterHook_ThenGetSecret_DoesNotRetroactivelyUpdatePoller verifies
// that getSecret called AFTER registerRefreshHook does not update the poller's version
func TestRegisterHook_ThenGetSecret_DoesNotRetroactivelyUpdatePoller(t *testing.T) {
	mockAuth := &MockAuthProvider{}
	mockAuth.On("GetAuthHeader").Return("Bearer test-token", nil)

	mockHTTPClient := &MockGrayskullHTTPClient{}

	secretValue := Client_API.SecretValue{
		DataVersion: 11,
		PublicPart:  "u",
		PrivatePart: "p",
	}
	resp := response.NewResponse(secretValue, "Success")
	jsonData, _ := json.Marshal(resp)

	mockHTTPClient.On("DoGetWithRetry", mock.Anything, mock.Anything, mock.Anything).
		Run(func(args mock.Arguments) {
			result := args.Get(2).(*response.Response[Client_API.SecretValue])
			json.Unmarshal(jsonData, result)
		}).
		Return(200, nil)

	config := &models.GrayskullClientConfiguration{
		Host:           "http://localhost:8080",
		MaxConnections: 10,
	}

	registry := internalHooks.NewRegistry()
	client := &GrayskullClientImpl{
		baseURL:            config.Host,
		authHeaderProvider: mockAuth,
		clientConfig:       config,
		httpClient:         mockHTTPClient,
		metricsRecorder:    metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		registry:           registry,
	}

	// Register hook first (no prior getSecret, so seed=0)
	hook := func(v Client_API.SecretValue) error { return nil }
	_, err := client.RegisterRefreshHook(context.Background(), "team:late-get", hook)
	assert.NoError(t, err)

	// Now call getSecret (after registration)
	_, err = client.GetSecret(context.Background(), "team:late-get")
	assert.NoError(t, err)

	// Verify lastKnownVersion is still 0 (not retroactively updated)
	state := registry.Get("team:late-get")
	assert.NotNil(t, state)
	assert.Equal(t, int32(0), state.LastKnownVersion.Load())
}

// TestGetSecret_DifferentSecret_DoesNotSeedUnrelatedHook verifies that
// versions for one secret don't bleed into another
func TestGetSecret_DifferentSecret_DoesNotSeedUnrelatedHook(t *testing.T) {
	mockAuth := &MockAuthProvider{}
	mockAuth.On("GetAuthHeader").Return("Bearer test-token", nil)

	mockHTTPClient := &MockGrayskullHTTPClient{}

	secretValue := Client_API.SecretValue{
		DataVersion: 5,
		PublicPart:  "u",
		PrivatePart: "p",
	}
	resp := response.NewResponse(secretValue, "Success")
	jsonData, _ := json.Marshal(resp)

	mockHTTPClient.On("DoGetWithRetry", mock.Anything, mock.Anything, mock.Anything).
		Run(func(args mock.Arguments) {
			result := args.Get(2).(*response.Response[Client_API.SecretValue])
			json.Unmarshal(jsonData, result)
		}).
		Return(200, nil)

	config := &models.GrayskullClientConfiguration{
		Host:           "http://localhost:8080",
		MaxConnections: 10,
	}

	registry := internalHooks.NewRegistry()
	client := &GrayskullClientImpl{
		baseURL:            config.Host,
		authHeaderProvider: mockAuth,
		clientConfig:       config,
		httpClient:         mockHTTPClient,
		metricsRecorder:    metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		registry:           registry,
	}

	// GetSecret for secret-a records v5
	_, err := client.GetSecret(context.Background(), "team:secret-a")
	assert.NoError(t, err)

	// Register hook for a different secret (secret-b)
	hook := func(v Client_API.SecretValue) error { return nil }
	_, err = client.RegisterRefreshHook(context.Background(), "team:secret-b", hook)
	assert.NoError(t, err)

	// Verify secret-b has lastKnownVersion=0 (not seeded from secret-a)
	state := registry.Get("team:secret-b")
	assert.NotNil(t, state)
	assert.Equal(t, int32(0), state.LastKnownVersion.Load())
}

// TestTwoHooksSameSecret_SecondRegistrationLeavesVersionIntact verifies
// that registering a second hook for the same secret doesn't overwrite lastKnownVersion
func TestTwoHooksSameSecret_SecondRegistrationLeavesVersionIntact(t *testing.T) {
	mockAuth := &MockAuthProvider{}
	mockAuth.On("GetAuthHeader").Return("Bearer test-token", nil)

	mockHTTPClient := &MockGrayskullHTTPClient{}

	secretValue := Client_API.SecretValue{
		DataVersion: 8,
		PublicPart:  "u",
		PrivatePart: "p",
	}
	resp := response.NewResponse(secretValue, "Success")
	jsonData, _ := json.Marshal(resp)

	mockHTTPClient.On("DoGetWithRetry", mock.Anything, mock.Anything, mock.Anything).
		Run(func(args mock.Arguments) {
			result := args.Get(2).(*response.Response[Client_API.SecretValue])
			json.Unmarshal(jsonData, result)
		}).
		Return(200, nil)

	config := &models.GrayskullClientConfiguration{
		Host:           "http://localhost:8080",
		MaxConnections: 10,
	}

	registry := internalHooks.NewRegistry()
	client := &GrayskullClientImpl{
		baseURL:            config.Host,
		authHeaderProvider: mockAuth,
		clientConfig:       config,
		httpClient:         mockHTTPClient,
		metricsRecorder:    metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		registry:           registry,
	}

	// GetSecret observes v8
	_, err := client.GetSecret(context.Background(), "team:shared")
	assert.NoError(t, err)

	// Register first hook (seeds lastKnownVersion=8)
	hook1 := func(v Client_API.SecretValue) error { return nil }
	_, err = client.RegisterRefreshHook(context.Background(), "team:shared", hook1)
	assert.NoError(t, err)

	// Register second hook (should not overwrite version)
	hook2 := func(v Client_API.SecretValue) error { return nil }
	_, err = client.RegisterRefreshHook(context.Background(), "team:shared", hook2)
	assert.NoError(t, err)

	// Verify lastKnownVersion is still 8
	state := registry.Get("team:shared")
	assert.NotNil(t, state)
	assert.Equal(t, int32(8), state.LastKnownVersion.Load())
}

// TestRegisterRefreshHook_AfterClose_Throws verifies that registering a hook
// after client is closed returns an error
func TestRegisterRefreshHook_AfterClose_Throws(t *testing.T) {
	mockAuth := &MockAuthProvider{}
	mockHTTPClient := &MockGrayskullHTTPClient{}
	mockHTTPClient.On("Close").Return(nil)

	config := &models.GrayskullClientConfiguration{
		Host:           "http://localhost:8080",
		MaxConnections: 10,
	}

	registry := internalHooks.NewRegistry()
	client := &GrayskullClientImpl{
		baseURL:            config.Host,
		authHeaderProvider: mockAuth,
		clientConfig:       config,
		httpClient:         mockHTTPClient,
		metricsRecorder:    metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		registry:           registry,
	}

	// Close the client
	client.Close()

	// Try to register a hook after close
	hook := func(v Client_API.SecretValue) error { return nil }
	ref, err := client.RegisterRefreshHook(context.Background(), "team:after-close", hook)

	assert.Error(t, err)
	assert.Nil(t, ref)
	assert.Contains(t, err.Error(), "client has been closed")
}

// TestClose_CleansUpResources verifies that Close properly cleans up resources
func TestClose_CleansUpResources(t *testing.T) {
	mockAuth := &MockAuthProvider{}
	mockHTTPClient := &MockGrayskullHTTPClient{}
	mockHTTPClient.On("Close").Return(nil)

	config := &models.GrayskullClientConfiguration{
		Host:           "http://localhost:8080",
		MaxConnections: 10,
	}

	registry := internalHooks.NewRegistry()
	client := &GrayskullClientImpl{
		baseURL:            config.Host,
		authHeaderProvider: mockAuth,
		clientConfig:       config,
		httpClient:         mockHTTPClient,
		metricsRecorder:    metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		registry:           registry,
	}

	err := client.Close()

	assert.NoError(t, err)
	mockHTTPClient.AssertCalled(t, "Close")
}

// TestClose_CalledTwice_IsIdempotent verifies that calling Close multiple times is safe
func TestClose_CalledTwice_IsIdempotent(t *testing.T) {
	mockAuth := &MockAuthProvider{}
	mockHTTPClient := &MockGrayskullHTTPClient{}
	mockHTTPClient.On("Close").Return(nil)

	config := &models.GrayskullClientConfiguration{
		Host:           "http://localhost:8080",
		MaxConnections: 10,
	}

	registry := internalHooks.NewRegistry()
	client := &GrayskullClientImpl{
		baseURL:            config.Host,
		authHeaderProvider: mockAuth,
		clientConfig:       config,
		httpClient:         mockHTTPClient,
		metricsRecorder:    metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		registry:           registry,
	}

	err1 := client.Close()
	err2 := client.Close()

	assert.NoError(t, err1)
	assert.NoError(t, err2)

	// Close should be called only once due to idempotency
	mockHTTPClient.AssertNumberOfCalls(t, "Close", 1)
}

// TestSplitSecretRef_WithColonsInSecretName verifies that secretNames with
// colons are handled correctly (splits on first colon only)
func TestSplitSecretRef_WithColonsInSecretName(t *testing.T) {
	mockAuth := &MockAuthProvider{}
	mockHTTPClient := &MockGrayskullHTTPClient{}

	config := &models.GrayskullClientConfiguration{
		Host:           "http://localhost:8080",
		MaxConnections: 10,
	}

	registry := internalHooks.NewRegistry()
	client := &GrayskullClientImpl{
		baseURL:            config.Host,
		authHeaderProvider: mockAuth,
		clientConfig:       config,
		httpClient:         mockHTTPClient,
		metricsRecorder:    metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		registry:           registry,
	}

	// Test indirectly through RegisterRefreshHook
	hook := func(v Client_API.SecretValue) error { return nil }
	ref, err := client.RegisterRefreshHook(context.Background(), "project:secret:with:colons", hook)

	assert.NoError(t, err)
	assert.NotNil(t, ref)
	assert.Equal(t, "project:secret:with:colons", ref.GetSecretRef())

	// Verify state was created correctly
	state := registry.Get("project:secret:with:colons")
	assert.NotNil(t, state)
	assert.Equal(t, "project", state.ProjectID)
	assert.Equal(t, "secret:with:colons", state.SecretName)
}

// TestConcurrentGetSecretAndRegisterHook verifies thread safety
func TestConcurrentGetSecretAndRegisterHook(t *testing.T) {
	mockAuth := &MockAuthProvider{}
	mockAuth.On("GetAuthHeader").Return("Bearer test-token", nil)

	mockHTTPClient := &MockGrayskullHTTPClient{}

	secretValue := Client_API.SecretValue{
		DataVersion: 1,
		PublicPart:  "p",
		PrivatePart: "p",
	}
	resp := response.NewResponse(secretValue, "Success")
	jsonData, _ := json.Marshal(resp)

	mockHTTPClient.On("DoGetWithRetry", mock.Anything, mock.Anything, mock.Anything).
		Run(func(args mock.Arguments) {
			result := args.Get(2).(*response.Response[Client_API.SecretValue])
			json.Unmarshal(jsonData, result)
		}).
		Return(200, nil)

	config := &models.GrayskullClientConfiguration{
		Host:           "http://localhost:8080",
		MaxConnections: 10,
	}

	registry := internalHooks.NewRegistry()
	client := &GrayskullClientImpl{
		baseURL:            config.Host,
		authHeaderProvider: mockAuth,
		clientConfig:       config,
		httpClient:         mockHTTPClient,
		metricsRecorder:    metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		registry:           registry,
	}

	const numGoroutines = 50
	var wg sync.WaitGroup
	wg.Add(numGoroutines * 2) // half GetSecret, half RegisterRefreshHook

	// Concurrent GetSecret calls
	for i := 0; i < numGoroutines; i++ {
		go func() {
			defer wg.Done()
			client.GetSecret(context.Background(), "team:concurrent")
		}()
	}

	// Concurrent RegisterRefreshHook calls
	for i := 0; i < numGoroutines; i++ {
		go func() {
			defer wg.Done()
			hook := func(v Client_API.SecretValue) error { return nil }
			client.RegisterRefreshHook(context.Background(), "team:concurrent", hook)
		}()
	}

	wg.Wait()
	// Test passes if no data race detected (run with -race flag)
}

// TestGetSecret_RecordsVersionInLastSeenVersions verifies that successful
// getSecret calls update lastSeenVersions
func TestGetSecret_RecordsVersionInLastSeenVersions(t *testing.T) {
	mockAuth := &MockAuthProvider{}
	mockAuth.On("GetAuthHeader").Return("Bearer test-token", nil)

	mockHTTPClient := &MockGrayskullHTTPClient{}

	secretValue := Client_API.SecretValue{
		DataVersion: 42,
		PublicPart:  "test",
		PrivatePart: "test",
	}
	resp := response.NewResponse(secretValue, "Success")
	jsonData, _ := json.Marshal(resp)

	mockHTTPClient.On("DoGetWithRetry", mock.Anything, mock.Anything, mock.Anything).
		Run(func(args mock.Arguments) {
			result := args.Get(2).(*response.Response[Client_API.SecretValue])
			json.Unmarshal(jsonData, result)
		}).
		Return(200, nil)

	config := &models.GrayskullClientConfiguration{
		Host:           "http://localhost:8080",
		MaxConnections: 10,
	}

	registry := internalHooks.NewRegistry()
	client := &GrayskullClientImpl{
		baseURL:            config.Host,
		authHeaderProvider: mockAuth,
		clientConfig:       config,
		httpClient:         mockHTTPClient,
		metricsRecorder:    metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		registry:           registry,
	}

	_, err := client.GetSecret(context.Background(), "team:recorded")
	assert.NoError(t, err)

	// Verify version was recorded in lastSeenVersions (via hook registration)
	// The lastSeenVersions is private, so we verify indirectly through hook registration
	hook2 := func(v Client_API.SecretValue) error { return nil }
	ref, err2 := client.RegisterRefreshHook(context.Background(), "team:recorded", hook2)
	assert.NoError(t, err2)

	// Verify the hook was seeded with version 42
	state := registry.Get("team:recorded")
	assert.NotNil(t, state)
	assert.Equal(t, int32(42), state.LastKnownVersion.Load())
	ref.Unregister()
}

// TestNewGrayskullClient_WithDefaultPollInterval verifies that the poller
// is started with the correct interval
func TestNewGrayskullClient_WithDefaultPollInterval(t *testing.T) {
	mockAuth := &MockAuthProvider{}

	config := models.NewDefaultConfig()
	config.Host = "http://localhost:8080"
	config.MaxConnections = 10
	config.PollingIntervalSeconds = 0 // Should use default

	client, err := NewGrayskullClient(mockAuth, config, metrics.NewPrometheusRecorder(prometheus.NewRegistry()))

	assert.NoError(t, err)
	assert.NotNil(t, client)

	// Clean up
	client.(*GrayskullClientImpl).Close()
}

// TestNewGrayskullClient_WithCustomPollInterval verifies custom polling interval
func TestNewGrayskullClient_WithCustomPollInterval(t *testing.T) {
	mockAuth := &MockAuthProvider{}

	config := models.NewDefaultConfig()
	config.Host = "http://localhost:8080"
	config.MaxConnections = 10
	config.PollingIntervalSeconds = 120 // 2 minutes

	client, err := NewGrayskullClient(mockAuth, config, metrics.NewPrometheusRecorder(prometheus.NewRegistry()))

	assert.NoError(t, err)
	assert.NotNil(t, client)

	implClient := client.(*GrayskullClientImpl)
	assert.NotNil(t, implClient.poller)

	// Clean up
	implClient.Close()
}

func TestClose_WithNilHttpClientReturnsNil(t *testing.T) {
	client := &GrayskullClientImpl{}
	err := client.Close()
	assert.NoError(t, err)
}
