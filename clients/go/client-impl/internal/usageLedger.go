package internal

import (
	"sync"

	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal/models/heartbeat"
)

// UsageLedger keeps per-secret usage for the heartbeat: one entry per secret,
// never removed, no secret values. The zero value is ready to use; methods on
// a nil *UsageLedger do nothing.
type UsageLedger struct {
	mu      sync.Mutex
	entries map[string]*ledgerEntry
}

type ledgerEntry struct {
	projectID          string
	secretName         string
	version            int
	hookCount          int
	hookAppliedVersion int
}

// RecordFetch records a successful GetSecret.
func (l *UsageLedger) RecordFetch(projectID, secretName string, version int) {
	l.RecordVersion(projectID, secretName, version)
}

// RecordVersion records a version seen by the poller.
func (l *UsageLedger) RecordVersion(projectID, secretName string, version int) {
	l.update(projectID, secretName, func(e *ledgerEntry) { e.version = max(e.version, version) })
}

// RecordHookRegistered counts a registered hook.
func (l *UsageLedger) RecordHookRegistered(projectID, secretName string) {
	l.update(projectID, secretName, func(e *ledgerEntry) { e.hookCount++ })
}

// RecordHookUnregistered uncounts a hook, never below zero.
func (l *UsageLedger) RecordHookUnregistered(projectID, secretName string) {
	l.update(projectID, secretName, func(e *ledgerEntry) { e.hookCount = max(0, e.hookCount-1) })
}

// RecordHookApplied records a successful hook run; failures are not recorded.
func (l *UsageLedger) RecordHookApplied(projectID, secretName string, version int) {
	l.update(projectID, secretName, func(e *ledgerEntry) {
		e.hookAppliedVersion = max(e.hookAppliedVersion, version)
	})
}

// Version returns the newest version held, or 0.
func (l *UsageLedger) Version(projectID, secretName string) int {
	if l == nil {
		return 0
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	if e, ok := l.entries[projectID+":"+secretName]; ok {
		return e.version
	}
	return 0
}

// Snapshot returns a copy of all entries; RefreshHookAppliedVersion is nil when
// no hook is registered.
func (l *UsageLedger) Snapshot() []heartbeat.HeldSecret {
	if l == nil {
		return nil
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	out := make([]heartbeat.HeldSecret, 0, len(l.entries))
	for _, e := range l.entries {
		var applied *int
		if e.hookCount > 0 {
			v := e.hookAppliedVersion
			applied = &v
		}
		out = append(out, heartbeat.HeldSecret{
			ProjectID:                 e.projectID,
			SecretName:                e.secretName,
			Version:                   e.version,
			RefreshHookAppliedVersion: applied,
		})
	}
	return out
}

func (l *UsageLedger) update(projectID, secretName string, apply func(e *ledgerEntry)) {
	if l == nil {
		return
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.entries == nil {
		l.entries = make(map[string]*ledgerEntry)
	}
	key := projectID + ":" + secretName
	e, ok := l.entries[key]
	if !ok {
		e = &ledgerEntry{projectID: projectID, secretName: secretName}
		l.entries[key] = e
	}
	apply(e)
}
