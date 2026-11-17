package internal

import (
	"sync"

	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal/models/heartbeat"
)

// UsageLedger keeps per-secret usage for the heartbeat
type UsageLedger struct {
	mu      sync.Mutex
	entries map[string]*ledgerEntry
}

type ledgerEntry struct {
	projectID          string
	secretName         string
	version            int
	hookRegistered     bool
	hookAppliedVersion int
	// hookGen changes whenever the set of registered hooks changes, so a delivery
	// that started before a hook was added cannot mark a version as applied.
	hookGen uint64
}

// RecordFetch records a successful GetSecret.
func (l *UsageLedger) RecordFetch(projectID, secretName string, version int) {
	l.RecordVersion(projectID, secretName, version)
}

// RecordVersion records a version seen by the poller.
func (l *UsageLedger) RecordVersion(projectID, secretName string, version int) {
	l.update(projectID, secretName, func(e *ledgerEntry) { e.version = max(e.version, version) })
}

// SetHookRegistered records whether any refresh hook is still registered for the secret.
func (l *UsageLedger) SetHookRegistered(projectID, secretName string, registered bool) {
	l.update(projectID, secretName, func(e *ledgerEntry) {
		e.hookRegistered = registered
		e.hookGen++
		if !registered {
			e.hookAppliedVersion = 0 // a hook registered later must apply a version itself
		}
	})
}

// RecordHookAdded records a newly added hook; it has applied nothing yet, so the applied version is cleared.
func (l *UsageLedger) RecordHookAdded(projectID, secretName string) {
	l.update(projectID, secretName, func(e *ledgerEntry) {
		e.hookRegistered = true
		e.hookAppliedVersion = 0
		e.hookGen++
	})
}

// HookGeneration returns the current hook generation; capture it before snapshotting hooks.
func (l *UsageLedger) HookGeneration(projectID, secretName string) uint64 {
	if l == nil {
		return 0
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	if e, ok := l.entries[projectID+":"+secretName]; ok {
		return e.hookGen
	}
	return 0
}

// RecordHookApplied records that every hook succeeded for version; it is ignored when
// hooks were added or removed since hookGen was captured. Failures are not recorded.
func (l *UsageLedger) RecordHookApplied(projectID, secretName string, version int, hookGen uint64) {
	l.update(projectID, secretName, func(e *ledgerEntry) {
		if e.hookGen == hookGen {
			e.hookAppliedVersion = max(e.hookAppliedVersion, version)
		}
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

// Snapshot returns a copy of all entries.
func (l *UsageLedger) Snapshot() []heartbeat.HeldSecret {
	if l == nil {
		return nil
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	out := make([]heartbeat.HeldSecret, 0, len(l.entries))
	for _, e := range l.entries {
		if e.version == 0 {
			continue // holds no version yet
		}
		var applied *int
		if e.hookRegistered {
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
