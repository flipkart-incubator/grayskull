package metrics

import (
	"net/url"
	"path"
	"regexp"
	"strings"
)

var (
	// UUIDRegex matches UUIDs in paths
	UUIDRegex = regexp.MustCompile(`[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}`)
	// NumericIDRegex matches numeric IDs in paths
	NumericIDRegex = regexp.MustCompile(`/\d+(/|$)`)
)

// NormalizeURL normalizes a URL path by replacing IDs with placeholders
func NormalizeURL(urlStr string) string {
	u, err := url.Parse(urlStr)
	if err != nil {
		return "invalid_url"
	}

	// Clean the path to remove any . or .. elements
	cleanPath := path.Clean(u.Path)

	// Replace UUIDs
	cleanPath = UUIDRegex.ReplaceAllString(cleanPath, "{uuid}")

	// Replace numeric IDs
	cleanPath = NumericIDRegex.ReplaceAllString(cleanPath, "/{id}$1")

	// Convert to lowercase for consistency
	cleanPath = strings.ToLower(cleanPath)

	return cleanPath
}
