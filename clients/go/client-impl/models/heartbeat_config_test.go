package models

import (
	"encoding/json"
	"testing"

	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/constants"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestHeartbeatConfig_EnabledByDefault(t *testing.T) {
	assert.True(t, NewDefaultConfig().IsHeartbeatEnabled())
	assert.True(t, (&GrayskullClientConfiguration{}).IsHeartbeatEnabled(), "a zero-value struct is enabled too")
}

func TestHeartbeatConfig_OmittedFieldsKeepDefaults(t *testing.T) {
	var cfg GrayskullClientConfiguration
	require.NoError(t, json.Unmarshal([]byte(`{"host":"http://x"}`), &cfg))
	assert.True(t, cfg.IsHeartbeatEnabled())

	defaults := NewDefaultConfig()
	require.NoError(t, json.Unmarshal([]byte(`{"host":"http://x"}`), defaults))
	assert.True(t, defaults.IsHeartbeatEnabled())
	assert.Equal(t, constants.DefaultHeartbeatIntervalSeconds, defaults.HeartbeatIntervalSeconds)
}

func TestHeartbeatConfig_ExplicitDisableAndZeroInterval(t *testing.T) {
	cfg := NewDefaultConfig()
	require.NoError(t, json.Unmarshal([]byte(`{"host":"http://x","heartbeatEnabled":false,"heartbeatIntervalSeconds":0}`), cfg))
	assert.False(t, cfg.IsHeartbeatEnabled())
	assert.Zero(t, cfg.HeartbeatIntervalSeconds, "zero is resolved to the default by the heartbeat")
}

func TestHeartbeatConfig_SetHeartbeatEnabled(t *testing.T) {
	cfg := &GrayskullClientConfiguration{}
	cfg.SetHeartbeatEnabled(false)
	assert.False(t, cfg.IsHeartbeatEnabled())
	cfg.SetHeartbeatEnabled(true)
	assert.True(t, cfg.IsHeartbeatEnabled())
}
