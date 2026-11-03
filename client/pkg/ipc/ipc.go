package ipc

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"

	"boostnet-client/pkg/downloader"
)

type HandoffRequest struct {
	URL       string `json:"url"`
	Filename  string `json:"filename"`
	Cookie    string `json:"cookie"`
	UserAgent string `json:"userAgent"`
	Referer   string `json:"referer"`
	AuthToken string `json:"token"`
}

type Server struct {
	dl        *downloader.Downloader
	authToken string
	server    *http.Server
}

func New(dl *downloader.Downloader, authToken string) *Server {
	return &Server{
		dl:        dl,
		authToken: authToken,
	}
}

func (s *Server) Start(port int) error {
	mux := http.NewServeMux()
	mux.HandleFunc("/rpc/download", s.handleDownload)
	mux.HandleFunc("/rpc/status", s.handleStatus)

	s.server = &http.Server{
		Addr:    fmt.Sprintf("127.0.0.1:%d", port),
		Handler: mux,
	}

	go func() {
		log.Printf("[IPC] Extension RPC loopback listening on 127.0.0.1:%d", port)
		if err := s.server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Printf("[IPC] Server error: %v", err)
		}
	}()

	return nil
}

func (s *Server) Stop() {
	if s.server != nil {
		_ = s.server.Shutdown(context.Background())
	}
}

func (s *Server) handleStatus(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"status":  "ready",
		"version": "2.0.0",
	})
}

func (s *Server) handleDownload(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
		return
	}

	var req HandoffRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "Bad Request", http.StatusBadRequest)
		return
	}

	// Verify ephemeral auth token
	if s.authToken != "" && req.AuthToken != s.authToken {
		http.Error(w, "Unauthorized", http.StatusUnauthorized)
		return
	}

	if req.URL == "" {
		http.Error(w, "Missing URL", http.StatusBadRequest)
		return
	}

	// Determine output path in user's Downloads directory
	userHome, _ := os.UserHomeDir()
	outDir := filepath.Join(userHome, "Downloads")
	filename := req.Filename
	if filename == "" {
		parts := strings.Split(req.URL, "/")
		filename = parts[len(parts)-1]
		if filename == "" || strings.Contains(filename, "?") {
			filename = "boostnet_download.bin"
		}
	}
	outPath := filepath.Join(outDir, filename)

	headers := make(map[string]string)
	if req.Cookie != "" {
		headers["Cookie"] = req.Cookie
	}
	if req.UserAgent != "" {
		headers["User-Agent"] = req.UserAgent
	}
	if req.Referer != "" {
		headers["Referer"] = req.Referer
	}

	go func() {
		dlReq := downloader.DownloadRequest{
			URL:        req.URL,
			OutputFile: outPath,
			Headers:    headers,
		}
		log.Printf("[IPC] Starting accelerated download for: %s -> %s", req.URL, outPath)
		err := s.dl.DownloadFile(context.Background(), dlReq, func(done, total int64) {
			// Telemetry / progress
		})
		if err != nil {
			log.Printf("[IPC] Download failed: %v", err)
		}
	}()

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"status": "started",
		"output": outPath,
	})
}
