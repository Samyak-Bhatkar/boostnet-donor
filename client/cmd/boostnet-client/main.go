package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"log"
	"os"
	"os/exec"
	"os/signal"
	"runtime"
	"syscall"
	"time"

	"boostnet-client/pkg/balancer"
	"boostnet-client/pkg/diagnostics"
	"boostnet-client/pkg/discovery"
	"boostnet-client/pkg/downloader"
	"boostnet-client/pkg/ipc"
	"boostnet-client/pkg/pairing"
	"boostnet-client/pkg/proxy"
	"boostnet-client/pkg/sysproxy"
)

func main() {
	// Check for CLI diagnose flag
	if len(os.Args) > 1 && (os.Args[1] == "diagnose" || os.Args[1] == "--diagnose-live" || os.Args[1] == "-d") {
		resp, err := http.Get("http://127.0.0.1:10801/rpc/diagnose")
		if err == nil {
			defer resp.Body.Close()
			body, _ := io.ReadAll(resp.Body)
			fmt.Println(string(body))
			os.Exit(0)
		}

		recorder, rErr := diagnostics.NewFlightRecorder("")
		if rErr == nil {
			recs, _ := recorder.ReadLastRecords(10)
			summary := map[string]interface{}{
				"status":  "live client not responding, displaying last flight recorder records",
				"records": recs,
			}
			out, _ := json.MarshalIndent(summary, "", "  ")
			fmt.Println(string(out))
			os.Exit(0)
		}
		log.Fatalf("No active BoostNet client running and no flight logs found.")
	}

	log.Println("==================================================")
	log.Println("     BoostNet 2.0 - Multipath Laptop Client       ")
	log.Println("==================================================")

	// 1. Startup self-healing: clear any lingering proxy settings from prior crash
	sysproxy.CheckAndSelfHealStartup()

	// 2. Generate cryptographically random pairing session
	hostname, _ := os.Hostname()
	if hostname == "" {
		hostname = "laptop"
	}
	session, err := pairing.LoadOrGenerateSession(hostname, "session.json")
	if err != nil {
		log.Fatalf("Failed to initialize cryptographic session: %v", err)
	}

	qrJSON, _ := session.QRPayloadJSON()
	pin := session.NumericPIN()
	confirmToken := session.VisualConfirmationCode()

	fmt.Println()
	fmt.Println("--- PAIRING CREDENTIALS ---")
	fmt.Printf("1. Open BoostNet Donor app on your phone\n")
	fmt.Printf("2. Tap 'Scan Laptop QR' and point camera at this screen\n")
	fmt.Printf("   Pairing Payload: %s\n", qrJSON)
	fmt.Printf("3. Or enter 6-digit PIN: [%s]\n", pin)
	fmt.Printf("4. Visual Confirmation Code: [%s]\n", confirmToken)
	fmt.Println("---------------------------")
	fmt.Println()

	// 3. Initialize discovery pool and listen for UDP beacons (:9999)
	pool := discovery.NewPool(session.DiscoveryKey())
	if err := pool.StartListener(9999); err != nil {
		log.Printf("[Warning] UDP discovery bind failed: %v. Continuing...", err)
	}

	// 4. Initialize SNI-sticky balancer and local proxy
	bal := balancer.New(pool)
	proxySrv := proxy.New(bal, session.MasterSecret)
	if err := proxySrv.StartHTTP(8080); err != nil {
		log.Fatalf("Failed to start local proxy on 127.0.0.1:8080: %v", err)
	}

	// 5. Initialize Web Dashboard, Download Accelerator & Browser Extension IPC (:10801)
	diagEngine, dErr := diagnostics.NewEngine(pool, session.ControlKey())
	if dErr != nil {
		log.Printf("[Warning] Diagnostic oracle failed to start: %v", dErr)
	}
	dl := downloader.New(pool, session.MasterSecret)
	ipcSrv := ipc.New(dl, session, pool, diagEngine)
	if err := ipcSrv.Start(10801); err != nil {
		log.Printf("[Warning] Dashboard/IPC start warning: %v", err)
	} else {
		log.Println("[Dashboard] Visual QR Dashboard active at: http://127.0.0.1:10801/")
		go func() {
			time.Sleep(300 * time.Millisecond)
			if runtime.GOOS == "windows" {
				_ = exec.Command("rundll32", "url.dll,FileProtocolHandler", "http://127.0.0.1:10801/").Start()
			}
		}()
	}

	// 6. Enable Windows system proxy
	if err := sysproxy.EnableProxy("127.0.0.1:8080"); err != nil {
		log.Printf("[Warning] Could not set system proxy automatically: %v", err)
	}

	// 7. Status monitor ticker
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	go func() {
		ticker := time.NewTicker(3 * time.Second)
		defer ticker.Stop()

		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				donors := pool.GetActiveDonors()
				if len(donors) == 0 {
					log.Printf("[Status] 0 phones connected. Waiting for BoostNet Donor to join hotspot...")
				} else {
					log.Printf("[Status] %d phone(s) bonded and active! Browsing is SNI-load-balanced; downloads accelerated.", len(donors))
					for i, d := range donors {
						log.Printf("   Node %d: %s (%s:%d) [Carrier: %s %s]",
							i+1, d.ID, d.IP, d.SocksPort, d.Carrier, d.Tech)
					}
				}
			}
		}
	}()

	// 8. Graceful shutdown handler
	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, os.Interrupt, syscall.SIGTERM)

	<-sigChan
	log.Println("\n[Shutdown] Shutting down BoostNet Client...")

	// Restore Windows system proxy on clean exit
	_ = sysproxy.DisableProxy()
	proxySrv.Stop()
	if diagEngine != nil { diagEngine.Stop() }
	ipcSrv.Stop()
	pool.Stop()

	log.Println("[Shutdown] Cleanup complete. Direct internet restored.")
}
