package internal

import (
	"sync"
	"time"

	"github.com/flipkart-incubator/grayskull/clients/go/client-impl/internal/models/heartbeat"
)

// maxHookErrorLength caps lastHookError.
const maxHookErrorLength = 256

// UsageLedger keeps per-secret usage for the heartbeat: one entry per secret,
// never removed, no secret values. The zero value is ready to use; methods on
// a nil *UsageLedger do nothing.
type UsageLedger struct {
	mu      sync.Mutex
	entries map[string]*ledgerEntry
	now     func() time.Time // nil means time.Now
}

type ledgerEntry struct {
	projectID          string
	secretName         string
	getCount           int64
	errorCount         int64
	firstAccessAt      int64
	lastAccessAt       int64
	version            int
	hookCount          int
	hookAppliedVersion int
	hookFailureCount   int64
	lastHookError      *string
}

// RecordGet records one GetSecret call.
func (l *UsageLedger) RecordGet(projectID, secretName string, version int, ok bool) {
	l.update(projectID, secretName, func(e *ledgerEntry, now int64) {
		if ok {
			e.getCount++
			e.version = max(e.version, version)
		} else {
			e.errorCount++
		}
		if e.firstAccessAt == 0 {
			e.firstAccessAt = now
		}
		e.lastAccessAt = now
	})
}

// RecordVersion records a version seen by the poller.
func (l *UsageLedger) RecordVersion(projectID, secretName string, version int) {
	l.update(projectID, secretName, func(e *ledgerEntry, _ int64) {
		e.version = max(e.version, version)
	})
}

// RecordHookRegistered counts a registered hook.
func (l *UsageLedger) RecordHookRegistered(projectID, secretName string) {
	l.update(projectID, secretName, func(e *ledgerEntry, _ int64) { e.hookCount++ })
}

// RecordHookUnregistered uncounts a hook, never below zero.
func (l *UsageLedger) RecordHookUnregistered(projectID, secretName string) {
	l.update(projectID, secretName, func(e *ledgerEntry, _ int64) { e.hookCount = max(0, e.hookCount-1) })
}

// RecordHookApplied records a successful hook run.
func (l *UsageLedger) RecordHookApplied(projectID, secretName string, version int) {
	l.update(projectID, secretName, func(e *ledgerEntry, _ int64) {
		e.hookAppliedVersion = max(e.hookAppliedVersion, version)
	})
}

// RecordHookFailed records a failed hook run.
func (l *UsageLedger) RecordHookFailed(projectID, secretName, description string) {
	l.update(projectID, secretName, func(e *ledgerEntry, _ int64) {
		e.hookFailureCount++
		d := truncateHookError(description)
		e.lastHookError = &d
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
		var lastErr *string
		if e.lastHookError != nil {
			s := *e.lastHookError
			lastErr = &s
		}
		out = append(out, heartbeat.HeldSecret{
			ProjectID:          e.projectID,
			SecretName:         e.secretName,
			Version:            e.version,
			RefreshHook:        e.hookCount > 0,
			GetCount:           e.getCount,
			ErrorCount:         e.errorCount,
			FirstAccessAt:      e.firstAccessAt,
			LastAccessAt:       e.lastAccessAt,
			HookAppliedVersion: e.hookAppliedVersion,
			HookFailureCount:   e.hookFailureCount,
			LastHookError:      lastErr,
		})
	}
	return out
}

func (l *UsageLedger) update(projectID, secretName string, apply func(e *ledgerEntry, nowMillis int64)) {
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
	now := time.Now
	if l.now != nil {
		now = l.now
	}
	apply(e, now().UnixMilli())
}

func truncateHookError(s string) string {
	if len(s) <= maxHookErrorLength {
		return s
	}
	return s[:maxHookErrorLength]
}
