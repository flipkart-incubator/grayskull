package client_impl

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"testing"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"

	Client_API "github.com/flipkart-incubator/grayskull/clients/go/client-api/models"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/auth"
	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal"
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

func (m *MockGrayskullHTTPClient) DoGetWithRetry(ctx context.Context, url string) (*response.HttpResponse[string], error) {
	args := m.Called(ctx, url)
	if args.Get(0) == nil {
		return nil, args.Error(1)
	}
	return args.Get(0).(*response.HttpResponse[string]), args.Error(1)
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
	}
}

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

			client, err := NewGrayskullClient(tt.authProvider, config, nil)

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
				resp := response.NewResponse(secretValue, "")
				jsonData, _ := json.Marshal(resp)

				m.On("DoGetWithRetry", mock.Anything, mock.Anything).
					Return(response.NewHttpResponse(http.StatusOK, string(jsonData), "", ""), nil)
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
		resp := response.NewResponse(secretValue, "success")
		jsonData, _ := json.Marshal(resp)

		mockHTTPClient := &MockGrayskullHTTPClient{}
		mockHTTPClient.On("DoGetWithRetry", mock.Anything, mock.Anything).
			Return(response.NewHttpResponse(200, string(jsonData), "", ""), nil)

		client := NewGrayskullClientForTesting(
			"http://localhost:8080",
			mockAuth,
			config,
			mockHTTPClient,
			metrics.NewPrometheusRecorder(prometheus.NewRegistry()),
		)

		result, err := client.GetSecret(nil, "project:secret")

		assert.NoError(t, err)
		assert.NotNil(t, result)
		assert.Equal(t, secretValue.PublicPart, result.PublicPart)
	})

	t.Run("HTTP error with nil response", func(t *testing.T) {
		mockHTTPClient := &MockGrayskullHTTPClient{}
		mockHTTPClient.On("DoGetWithRetry", mock.Anything, mock.Anything).
			Return(nil, errors.New("network error"))

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
		mockHTTPClient.On("DoGetWithRetry", mock.Anything, mock.Anything).
			Return(response.NewHttpResponse(500, "Internal Server Error", "", ""), errors.New("server error"))

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
		mockHTTPClient.On("DoGetWithRetry", mock.Anything, mock.Anything).
			Return(response.NewHttpResponse(200, "invalid json", "", ""), nil)

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
		assert.Contains(t, err.Error(), "failed to parse response")
	})

	t.Run("empty data in response", func(t *testing.T) {
		resp := response.NewResponse(Client_API.SecretValue{}, "success")
		jsonData, _ := json.Marshal(resp)

		mockHTTPClient := &MockGrayskullHTTPClient{}
		mockHTTPClient.On("DoGetWithRetry", mock.Anything, mock.Anything).
			Return(response.NewHttpResponse(200, string(jsonData), "", ""), nil)

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
		})).Return(response.NewHttpResponse(200, string(jsonData), "", ""), nil)

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
