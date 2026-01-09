package response

// HttpResponse represents a raw HTTP response.
//
// This struct captures the essential parts of an HTTP response needed for processing:
// status code, body, content type, and protocol. It is immutable and thread-safe.
type HttpResponse struct {
	statusCode  int
	body        string
	contentType string
	protocol    string
}

// NewHttpResponse creates a new immutable HttpResponse instance.
func NewHttpResponse(statusCode int, body, contentType, protocol string) *HttpResponse {
	return &HttpResponse{
		statusCode:  statusCode,
		body:        body,
		contentType: contentType,
		protocol:    protocol,
	}
}

// StatusCode returns the HTTP status code of the response.
func (r *HttpResponse) StatusCode() int {
	return r.statusCode
}

// Body returns the response body as a string.
func (r *HttpResponse) Body() string {
	return r.body
}

// ContentType returns the Content-Type header value of the response.
func (r *HttpResponse) ContentType() string {
	return r.contentType
}

// Protocol returns the HTTP protocol version used in the response.
func (r *HttpResponse) Protocol() string {
	return r.protocol
}
