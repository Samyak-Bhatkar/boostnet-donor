package proxy

import (
	"context"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"boostnet-client/pkg/balancer"
	"golang.org/x/net/proxy"
)

type Server struct {
	balancer   *balancer.Balancer
	authSecret string
	httpServer *http.Server
	socksLn    net.Listener
	mu         sync.Mutex
	closed     bool
}

func New(b *balancer.Balancer, authSecret string) *Server {
	return &Server{
		balancer:   b,
		authSecret: authSecret,
	}
}

// StartHTTP starts the local HTTP / CONNECT proxy on 127.0.0.1:port.
func (s *Server) StartHTTP(port int) error {
	addr := fmt.Sprintf("127.0.0.1:%d", port)
	s.httpServer = &http.Server{
		Addr: addr,
		Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.Method == http.MethodConnect {
				s.handleConnect(w, r)
			} else {
				s.handleHTTP(w, r)
			}
		}),
	}

	ln, err := net.Listen("tcp4", addr)
	if err != nil {
		return fmt.Errorf("failed to bind HTTP proxy to %s: %w", addr, err)
	}

	go func() {
		log.Printf("[Proxy] Local HTTP/CONNECT proxy active on %s", addr)
		if err := s.httpServer.Serve(ln); err != nil && err != http.ErrServerClosed {
			log.Printf("[Proxy] HTTP proxy error: %v", err)
		}
	}()

	return nil
}

// handleConnect handles HTTPS tunneling (HTTP CONNECT domain:443).
func (s *Server) handleConnect(w http.ResponseWriter, r *http.Request) {
	destHost := r.Host
	donor, err := s.balancer.PickDonor(destHost)

	var upstreamConn net.Conn
	if err == nil && donor != nil {
		// Tunnel through donor SOCKS5
		socksAddr := fmt.Sprintf("%s:%d", donor.IP.String(), donor.SocksPort)
		pwd := s.authSecret
		if pwd == "" {
			pwd = "password"
		}
		dialer, sErr := proxy.SOCKS5("tcp", socksAddr, &proxy.Auth{
			User:     "boostnet",
			Password: pwd,
		}, proxy.Direct)

		if sErr == nil {
			upstreamConn, err = dialer.Dial("tcp", destHost)
		}
	}

	// Fallback to direct connection if no donor or donor connection failed
	if upstreamConn == nil {
		upstreamConn, err = net.DialTimeout("tcp", destHost, 10*time.Second)
		if err != nil {
			http.Error(w, fmt.Sprintf("Failed to connect to %s: %v", destHost, err), http.StatusServiceUnavailable)
			return
		}
	}
	defer upstreamConn.Close()

	hijacker, ok := w.(http.Hijacker)
	if !ok {
		http.Error(w, "Hijacking not supported", http.StatusInternalServerError)
		return
	}

	clientConn, _, err := hijacker.Hijack()
	if err != nil {
		http.Error(w, err.Error(), http.StatusServiceUnavailable)
		return
	}
	defer clientConn.Close()

	// 200 Connection Established
	_, _ = clientConn.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n"))

	// Bidirectional pipe with half-close handling
	pipe(clientConn, upstreamConn)
}

func (s *Server) handleHTTP(w http.ResponseWriter, r *http.Request) {
	// Standard HTTP forwarding
	destHost := r.Host
	if !strings.Contains(destHost, ":") {
		destHost += ":80"
	}

	donor, err := s.balancer.PickDonor(destHost)
	var transport *http.Transport

	if err == nil && donor != nil {
		socksAddr := fmt.Sprintf("%s:%d", donor.IP.String(), donor.SocksPort)
		pwd := s.authSecret
		if pwd == "" {
			pwd = "password"
		}
		dialer, sErr := proxy.SOCKS5("tcp", socksAddr, &proxy.Auth{
			User:     "boostnet",
			Password: pwd,
		}, proxy.Direct)
		if sErr == nil {
			transport = &http.Transport{
				DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
					return dialer.Dial(network, addr)
				},
			}
		}
	}

	if transport == nil {
		transport = http.DefaultTransport.(*http.Transport).Clone()
	}

	outReq := r.Clone(r.Context())
	outReq.RequestURI = ""

	resp, err := transport.RoundTrip(outReq)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()

	for k, vv := range resp.Header {
		for _, v := range vv {
			w.Header().Add(k, v)
		}
	}
	w.WriteHeader(resp.StatusCode)
	_, _ = io.Copy(w, resp.Body)
}

func pipe(c1, c2 net.Conn) {
	var wg sync.WaitGroup
	wg.Add(2)

	go func() {
		defer wg.Done()
		_, _ = io.Copy(c1, c2)
		if tcpConn, ok := c1.(*net.TCPConn); ok {
			_ = tcpConn.CloseWrite()
		}
	}()

	go func() {
		defer wg.Done()
		_, _ = io.Copy(c2, c1)
		if tcpConn, ok := c2.(*net.TCPConn); ok {
			_ = tcpConn.CloseWrite()
		}
	}()

	wg.Wait()
}

func (s *Server) Stop() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.closed = true
	if s.httpServer != nil {
		_ = s.httpServer.Shutdown(context.Background())
	}
	if s.socksLn != nil {
		_ = s.socksLn.Close()
	}
}
