package balancer

import (
	"crypto/sha256"
	"fmt"
	"net"
	"strings"
	"sync"

	"boostnet-client/pkg/discovery"
	"golang.org/x/net/publicsuffix"
)

// Balancer implements SNI-sticky per-domain consistent hashing load-balancing.
type Balancer struct {
	pool *discovery.Pool
	mu   sync.RWMutex
}

func New(pool *discovery.Pool) *Balancer {
	return &Balancer{
		pool: pool,
	}
}

// ExtractRegistrableDomain uses the official Public Suffix List (PSL)
// to correctly parse multi-part TLDs (.co.uk, .com.au, .gov.in)
// preventing all regional sites from hashing to the same donor.
func ExtractRegistrableDomain(host string) string {
	// Strip port if present
	h, _, err := net.SplitHostPort(host)
	if err == nil {
		host = h
	}
	host = strings.ToLower(strings.TrimSpace(host))

	// If IP literal, return as-is
	if net.ParseIP(host) != nil {
		return host
	}

	// Use PSL to extract effective TLD+1
	eTLDPlusOne, err := publicsuffix.EffectiveTLDPlusOne(host)
	if err != nil {
		// Fallback to full host if public suffix lookup fails
		return host
	}
	return eTLDPlusOne
}

// PickDonor selects an active donor node for the requested target host.
// If the domain is stateful (banking, auth), consistent hashing ensures it is pinned
// to a single donor. If CDN/media, it is balanced across available donors.
func (b *Balancer) PickDonor(targetHost string) (*discovery.DonorNode, error) {
	donors := b.pool.GetActiveDonors()
	if len(donors) == 0 {
		return nil, fmt.Errorf("no active donor phones available")
	}

	rootDomain := ExtractRegistrableDomain(targetHost)

	// Check if this is a heavy CDN / media chunk domain that should be split
	if isMediaOrCdn(targetHost) {
		// Round-robin or hash on full hostname + timestamp/random for CDN multiplexing
		hash := sha256.Sum256([]byte(targetHost))
		idx := int(hash[0]) % len(donors)
		return donors[idx], nil
	}

	// Standard web / banking / auth domain: strictly pin to consistent donor
	hash := sha256.Sum256([]byte(rootDomain))
	val := int(hash[0])*256 + int(hash[1])
	idx := val % len(donors)
	return donors[idx], nil
}

func isMediaOrCdn(host string) bool {
	lower := strings.ToLower(host)
	cdnPatterns := []string{
		"googlevideo.com", "fbcdn.net", "akamai", "cloudfront.net",
		"fastly.net", "cdn", "telemetry", "static", "assets",
		"steamcontent.com", "nflxvideo.net",
	}
	for _, p := range cdnPatterns {
		if strings.Contains(lower, p) {
			return true
		}
	}
	return false
}
