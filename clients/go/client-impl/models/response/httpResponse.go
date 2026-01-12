package response

type HttpResponse struct {
	StatusCode  int
	Body        string
	ContentType string
	Protocol    string
}
