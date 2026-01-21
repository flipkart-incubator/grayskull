package response

type HttpResponse[T any] struct {
	statusCode int
	body       T
}

func (h *HttpResponse[T]) StatusCode() int {
	return h.statusCode
}

func (h *HttpResponse[T]) Body() T {
	return h.body
}

// NewHttpResponse creates a new HttpResponse with the given values
func NewHttpResponse[T any](statusCode int, body T, contentType string, protocol string) *HttpResponse[T] {
	return &HttpResponse[T]{
		statusCode: statusCode,
		body:       body,
	}
}
