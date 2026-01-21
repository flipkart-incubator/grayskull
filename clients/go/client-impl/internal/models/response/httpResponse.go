package response

type HttpResponse[T any] struct {
	StatusCode int
	Body       T
}

// NewHttpResponse creates a new HttpResponse with the given values
func NewHttpResponse[T any](statusCode int, body T) *HttpResponse[T] {
	return &HttpResponse[T]{
		StatusCode: statusCode,
		Body:       body,
	}
}
