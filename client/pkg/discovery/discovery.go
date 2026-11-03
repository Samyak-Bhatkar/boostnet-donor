package discovery

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	"net"
	"sync"
	"time"
)

// DonorNode represents an active, authenticated phone node discovered on the hotspot.
type DonorNode struct {
	ID        string    `json:"id"`
	IP        net.IP    `json:"ip"`
	SocksPort int       `json:"port"`
	CtrlPort  int       `json:"ctrlPort"`
	State     string    `json:"state"`
	Paired    bool      `json:"paired"`
	Status    string    `json:"status"`
	Carrier   string    `json:"carrier"`
	Tech      string    `json:"tech"`
	LastSeen  time.Time `json:"lastSeen"`
}

// BeaconPacket represents the UDP payload emitted by the Android app.
type BeaconPacket struct {
	Version  int    `json:"v"`
	Type     string `json:"type"`
	ID       string `json:"id"`
	Seq      int64  `json:"seq"`
	Port     int    `json:"port"`
	CtrlPort int    `json:"ctrlPort"`
	State    string `json:"state"`
	Paired   bool   `json:"paired"`
	Status   string `json:"status"`
	HMAC     string `json:"hmac"`
	TS       int64  `json:"ts"`
}

type Pool struct {
	mu           sync.RWMutex
	donors       map[string]*DonorNode
	discoveryKey []byte
	stopChan     chan struct{}
}

func NewPool(discoveryKey []byte) *Pool {
	return &Pool{
		donors:       make(map[string]*DonorNode),
		discoveryKey: discoveryKey,
		stopChan:     make(chan struct{}),
	}
}

// StartListener listens on UDP 9999 for directed subnet beacons.
func (p *Pool) StartListener(port int) error {
	addr := net.UDPAddr{
		Port: port,
		IP:   net.IPv4zero,
	}

	conn, err := net.ListenUDP("udp4", &addr)
	if err != nil {
		return fmt.Errorf("failed to bind UDP discovery port %d: %w", port, err)
	}

	go func() {
		defer conn.Close()
		buf := make([]byte, 4096)

		for {
			select {
			case <-p.stopChan:
				return
			default:
			}

			_ = conn.SetReadDeadline(time.Now().Add(1 * time.Second))
			n, remoteAddr, err := conn.ReadFromUDP(buf)
			if err != nil {
				continue
			}

			var beacon BeaconPacket
			if err := json.Unmarshal(buf[:n], &beacon); err != nil {
				continue
			}

			// Validate packet
			if beacon.Type != "hello" || beacon.ID == "" {
				continue
			}

			// Verify HMAC if paired
			if beacon.Paired && len(p.discoveryKey) > 0 {
				msg := fmt.Sprintf("1|%s|%d|%d|%d|%s", beacon.ID, beacon.Seq, beacon.Port, beacon.CtrlPort, beacon.State)
				mac := hmac.New(sha256.New, p.discoveryKey)
				mac.Write([]byte(msg))
				expectedHMAC := hex.EncodeToString(mac.Sum(nil))

				if !hmac.Equal([]byte(beacon.HMAC), []byte(expectedHMAC)) {
					log.Printf("[Discovery] Rejecting beacon with invalid HMAC from %s (%s)", beacon.ID, remoteAddr.IP)
					continue
				}
			}

			p.mu.Lock()
			node, exists := p.donors[beacon.ID]
			if !exists {
				node = &DonorNode{
					ID: beacon.ID,
				}
				p.donors[beacon.ID] = node
				log.Printf("[Discovery] Discovered new donor node %s at %s:%d (State: %s, Paired: %v)",
					beacon.ID, remoteAddr.IP, beacon.Port, beacon.State, beacon.Paired)
			}

			node.IP = remoteAddr.IP
			node.SocksPort = beacon.Port
			node.CtrlPort = beacon.CtrlPort
			node.State = beacon.State
			node.Paired = beacon.Paired
			node.Status = beacon.Status
			node.LastSeen = time.Now()
			p.mu.Unlock()
		}
	}()

	// Pruning goroutine for stale donors (timeout after 6 seconds)
	go func() {
		ticker := time.NewTicker(3 * time.Second)
		defer ticker.Stop()

		for {
			select {
			case <-p.stopChan:
				return
			case <-ticker.C:
				p.mu.Lock()
				now := time.Now()
				for id, node := range p.donors {
					if now.Sub(node.LastSeen) > 6*time.Second {
						log.Printf("[Discovery] Node %s timed out. Removing from active pool.", id)
						delete(p.donors, id)
					}
				}
				p.mu.Unlock()
			}
		}
	}()

	return nil
}

func (p *Pool) Stop() {
	close(p.stopChan)
}

// GetActiveDonors returns all currently healthy donor nodes.
func (p *Pool) GetActiveDonors() []*DonorNode {
	p.mu.RLock()
	defer p.mu.RUnlock()

	var result []*DonorNode
	for _, node := range p.donors {
		if node.State == "ACTIVE" || node.State == "THROTTLED" {
			result = append(result, node)
		}
	}
	return result
}
