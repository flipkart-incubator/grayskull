package response

import "testing"

func TestNewResponse_PopulatesFields(t *testing.T) {
	resp := NewResponse(map[string]int{"k": 1}, "ok")
	if resp == nil {
		t.Fatal("NewResponse returned nil")
	}
	if resp.Message != "ok" {
		t.Fatalf("Message = %q, want %q", resp.Message, "ok")
	}
	if got := resp.Data["k"]; got != 1 {
		t.Fatalf("Data[k] = %d, want %d", got, 1)
	}
}

