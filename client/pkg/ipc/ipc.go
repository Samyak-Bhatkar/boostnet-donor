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

	"boostnet-client/pkg/discovery"
	"boostnet-client/pkg/diagnostics"
	"boostnet-client/pkg/downloader"
	"boostnet-client/pkg/pairing"
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
	session   *pairing.Session
	pool      *discovery.Pool
	diagEngine *diagnostics.Engine
	authToken string
	server    *http.Server
}

func New(dl *downloader.Downloader, session *pairing.Session, pool *discovery.Pool, diagEngine *diagnostics.Engine) *Server {
	authToken := ""
	if session != nil && len(session.MasterSecret) >= 16 {
		authToken = session.MasterSecret[:16]
	}
	return &Server{
		dl:        dl,
		session:   session,
		pool:      pool,
		diagEngine: diagEngine,
		authToken: authToken,
	}
}

func (s *Server) Start(port int) error {
	mux := http.NewServeMux()
	mux.HandleFunc("/", s.handleDashboard)
	mux.HandleFunc("/rpc/download", s.handleDownload)
	mux.HandleFunc("/rpc/status", s.handleStatus)
	mux.HandleFunc("/rpc/donors", s.handleDonors)
	mux.HandleFunc("/rpc/diagnose", s.handleDiagnose)

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

func (s *Server) handleDonors(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	donors := []*discovery.DonorNode{}
	if s.pool != nil {
		donors = s.pool.GetActiveDonors()
	}
	_ = json.NewEncoder(w).Encode(donors)
}

func (s *Server) handleDashboard(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" && r.URL.Path != "/qr" {
		http.NotFound(w, r)
		return
	}

	payload := ""
	pin := ""
	confirmCode := ""
	if s.session != nil {
		payload, _ = s.session.QRPayloadJSON()
		pin = s.session.NumericPIN()
		confirmCode = s.session.VisualConfirmationCode()
	}

	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	fmt.Fprintf(w, `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>BoostNet 2.0 - Pair Donor Phones</title>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; }
  body { background: #0F172A; color: #F8FAFC; min-height: 100vh; display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 20px; }
  .card { background: #1E293B; border: 1px solid #334155; border-radius: 16px; padding: 32px; max-width: 480px; width: 100%%; text-align: center; box-shadow: 0 10px 25px -5px rgba(0,0,0,0.5); }
  h1 { font-size: 24px; font-weight: 700; color: #38BDF8; margin-bottom: 8px; }
  p.subtitle { color: #94A3B8; font-size: 14px; margin-bottom: 24px; }
  .qr-box { background: #FFFFFF; padding: 16px; border-radius: 12px; display: inline-block; margin-bottom: 20px; box-shadow: 0 4px 12px rgba(0,0,0,0.3); }
  #qrcode canvas, #qrcode img { display: block; margin: 0 auto; }
  .badges { display: flex; gap: 12px; justify-content: center; margin-bottom: 20px; }
  .badge { background: #0F172A; border: 1px solid #334155; border-radius: 8px; padding: 10px 16px; }
  .badge-label { font-size: 11px; text-transform: uppercase; letter-spacing: 0.5px; color: #94A3B8; margin-bottom: 4px; }
  .badge-val { font-size: 20px; font-weight: 700; color: #38BDF8; font-family: monospace; }
  .payload-box { background: #0F172A; border: 1px solid #334155; border-radius: 8px; padding: 12px; text-align: left; font-size: 12px; color: #94A3B8; font-family: monospace; word-break: break-all; margin-bottom: 16px; position: relative; }
  .copy-btn { background: #38BDF8; color: #0F172A; border: none; font-weight: 600; padding: 10px 16px; border-radius: 8px; cursor: pointer; width: 100%%; font-size: 14px; transition: background 0.2s; }
  .copy-btn:hover { background: #0284C7; }
  .status-pill { display: inline-flex; align-items: center; gap: 6px; padding: 6px 12px; border-radius: 20px; font-size: 13px; font-weight: 500; background: #064E3B; color: #34D399; margin-top: 16px; }
  .pulse { width: 8px; height: 8px; background: #34D399; border-radius: 50%%; animation: blink 1.5s infinite; }
  @keyframes blink { 0%%, 100%% { opacity: 1; } 50%% { opacity: 0.4; } }
</style>
<script src="https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js"></script>
</head>
<body>
<div class="card">
  <h1>BoostNet Pairing</h1>
  <p class="subtitle">Open BoostNet on your phone & scan this code</p>
  
  <div class="qr-box">
    <div id="qrcode"></div>
  </div>

  <div class="badges">
    <div class="badge">
      <div class="badge-label">PIN Code</div>
      <div class="badge-val">%s</div>
    </div>
    <div class="badge">
      <div class="badge-label">Verify Code</div>
      <div class="badge-val">%s</div>
    </div>
  </div>

  <div class="payload-box" id="rawPayload">%s</div>
  <button class="copy-btn" onclick="copySecret()">Copy Pairing Secret</button>

  <div>
    <div class="status-pill" id="statusPill">
      <div class="pulse"></div>
      <span id="donorCount">Waiting for phone to scan...</span>
    </div>
  </div>
</div>

<script>
  const payload = %q;
  try {
    new QRCode(document.getElementById("qrcode"), {
      text: payload,
      width: 200,
      height: 200,
      colorDark: "#000000",
      colorLight: "#ffffff",
      correctLevel: QRCode.CorrectLevel.M
    });
  } catch (e) {
    document.getElementById("qrcode").innerHTML = "<p style='color:black;padding:20px;font-size:12px;'>Scan via payload or PIN</p>";
  }

  function copySecret() {
    try {
      const data = JSON.parse(payload);
      navigator.clipboard.writeText(data.secret);
      alert("Pairing secret copied to clipboard! Paste it into the BoostNet app.");
    } catch(e) {
      navigator.clipboard.writeText(payload);
      alert("Payload copied!");
    }
  }

  // Poll for connected donor status
  setInterval(async () => {
    try {
      const res = await fetch('/rpc/donors');
      const donors = await res.json();
      const count = donors ? donors.length : 0;
      const el = document.getElementById('donorCount');
      if (count > 0) {
        el.innerText = count + ' Phone(s) Bonded & Active!';
        document.getElementById('statusPill').style.background = '#064E3B';
        document.getElementById('statusPill').style.color = '#34D399';
      } else {
        el.innerText = 'Waiting for phone to scan...';
      }
    } catch(e) {}
  }, 2000);
</script>
</body>
</html>`, pin, confirmCode, payload, payload)
}

func (s *Server) handleDiagnose(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	if s.diagEngine != nil {
		jsonStr, err := s.diagEngine.DiagnoseJSON()
		if err == nil {
			_, _ = w.Write([]byte(jsonStr))
			return
		}
	}
	_ = json.NewEncoder(w).Encode(map[string]interface{}{"status": "diagnostics unavailable"})
}
