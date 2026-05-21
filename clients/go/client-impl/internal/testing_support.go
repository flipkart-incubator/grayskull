package internal

import "time"

// SetShutdownAwaitForTest overrides shutdownAwait and returns the previous
// value. Test-only; exposed for cross-package tests.
func SetShutdownAwaitForTest(d time.Duration) time.Duration {
	prev := shutdownAwait
	shutdownAwait = d
	return prev
}

// RestoreShutdownAwaitForTest restores a value from SetShutdownAwaitForTest.
func RestoreShutdownAwaitForTest(d time.Duration) {
	shutdownAwait = d
}

// AddStuckWorkerForTest bumps the WaitGroup so Close cannot drain,
// forcing the ErrShutdownTimeout path.
func AddStuckWorkerForTest(p *Poller) {
	p.wg.Add(1)
}
