package diagnostics

import (
	"bufio"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"

	"boostnet-client/pkg/discovery"
)

type NodeMetrics struct {
	NodeID            string
	IP                string
	CtrlPort          int
	LocalRTTMs        float64
	PacketLossPct     float64
	ClockOffsetNs     int64
	PhoneMonoNs       int64
	LastPingSuccess   time.Time
	ConsecutiveLosses int
	LatestGroundTruth map[string]interface{}
	LatestTelemetry   map[string]interface{}
	LastError         map[string]interface{}
	RecentRTTs        []float64
	RecentLosses      []bool
}

type LinkProber struct {
	mu         sync.RWMutex
	pool       *discovery.Pool
	controlKey []byte
	recorder   *FlightRecorder
	nodes      map[string]*NodeMetrics
	stopChan   chan struct{}
}

func NewLinkProber(pool *discovery.Pool, controlKey []byte, recorder *FlightRecorder) *LinkProber {
	return &LinkProber{
		pool:       pool,
		controlKey: controlKey,
		recorder:   recorder,
		nodes:      make(map[string]*NodeMetrics),
		stopChan:   make(chan struct{}),
	}
}

func (p *LinkProber) Start() {
	go func() {
		ticker := time.NewTicker(1 * time.Second)
		defer ticker.Stop()

		for {
			select {
			case <-p.stopChan:
				return
			case <-ticker.C:
				p.probeActiveNodes()
			}
		}
	}()
}

func (p *LinkProber) Stop() {
	close(p.stopChan)
}

func (p *LinkProber) GetNodeMetrics(nodeID string) *NodeMetrics {
	p.mu.RLock()
	defer p.mu.RUnlock()
	if m, ok := p.nodes[nodeID]; ok {
		cp := *m
		return &cp
	}
	return nil
}

func (p *LinkProber) GetAllMetrics() []*NodeMetrics {
	p.mu.RLock()
	defer p.mu.RUnlock()
	var list []*NodeMetrics
	for _, m := range p.nodes {
		cp := *m
		list = append(list, &cp)
	}
	return list
}

func (p *LinkProber) probeActiveNodes() {
	donors := p.pool.GetActiveDonors()
	for _, donor := range donors {
		go p.probeSingleNode(donor)
	}
}

func (p *LinkProber) probeSingleNode(donor *discovery.DonorNode) {
	nodeID := donor.ID
	p.mu.Lock()
	m, exists := p.nodes[nodeID]
	if !exists {
		m = &NodeMetrics{
			NodeID:   nodeID,
			IP:       donor.IP.String(),
			CtrlPort: donor.CtrlPort,
		}
		p.nodes[nodeID] = m
	}
	p.mu.Unlock()

	target := fmt.Sprintf("%s:%d", donor.IP.String(), donor.CtrlPort)
	start := time.Now()
	conn, err := net.DialTimeout("tcp", target, 800*time.Millisecond)
	if err != nil {
		p.recordLoss(nodeID)
		return
	}
	defer conn.Close()

	_ = conn.SetDeadline(time.Now().Add(1200 * time.Millisecond))
	reader := bufio.NewReader(conn)
	writer := bufio.NewWriter(conn)

	// Read Challenge
	challengeLine, err := reader.ReadString('\n')
	if err != nil || !strings.HasPrefix(challengeLine, "CHALLENGE ") {
		p.recordLoss(nodeID)
		return
	}
	nonce := strings.TrimSpace(strings.TrimPrefix(challengeLine, "CHALLENGE "))

	// Compute HMAC response
	// ControlKey = HKDF("boostnet-control-auth-v1", 32)
	mac := hmac.New(sha256.New, p.controlKey)
	mac.Write([]byte(nonce))
	authHmac := hex.EncodeToString(mac.Sum(nil))

	_, _ = writer.WriteString(fmt.Sprintf("AUTH %s\n", authHmac))
	_ = writer.Flush()

	authResp, err := reader.ReadString('\n')
	if err != nil || !strings.HasPrefix(authResp, "OK_AUTH") {
		// Even if auth fails due to pairing mismatch, we measure the link RTT!
	}

	// Send PING
	clientMonoNs := time.Now().UnixNano()
	_, _ = writer.WriteString(fmt.Sprintf("PING %d\n", clientMonoNs))
	_ = writer.Flush()

	pongLine, err := reader.ReadString('\n')
	recvTimeNs := time.Now().UnixNano()
	if err != nil || !strings.HasPrefix(pongLine, "PONG") {
		p.recordLoss(nodeID)
		return
	}

	parts := strings.Fields(strings.TrimSpace(pongLine))
	var phoneMonoNs int64
	if len(parts) >= 3 {
		phoneMonoNs, _ = strconv.ParseInt(parts[2], 10, 64)
	}

	rttDuration := time.Since(start)
	rttMs := float64(rttDuration.Microseconds()) / 1000.0

	// NTP mid-point clock offset
	var offsetNs int64
	if phoneMonoNs > 0 {
		midPoint := (clientMonoNs + recvTimeNs) / 2
		offsetNs = phoneMonoNs - midPoint
	}

	p.recordSuccess(nodeID, rttMs, offsetNs, phoneMonoNs)
}

func (p *LinkProber) recordLoss(nodeID string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	m, ok := p.nodes[nodeID]
	if !ok {
		return
	}

	m.ConsecutiveLosses++
	m.RecentLosses = append(m.RecentLosses, true)
	if len(m.RecentLosses) > 10 {
		m.RecentLosses = m.RecentLosses[1:]
	}

	lossCount := 0
	for _, l := range m.RecentLosses {
		if l {
			lossCount++
		}
	}
	m.PacketLossPct = float64(lossCount) / float64(len(m.RecentLosses)) * 100.0
}

func (p *LinkProber) recordSuccess(nodeID string, rttMs float64, offsetNs int64, phoneMonoNs int64) {
	p.mu.Lock()
	defer p.mu.Unlock()
	m, ok := p.nodes[nodeID]
	if !ok {
		return
	}

	m.ConsecutiveLosses = 0
	m.LastPingSuccess = time.Now()
	m.LocalRTTMs = rttMs
	m.ClockOffsetNs = offsetNs
	m.PhoneMonoNs = phoneMonoNs

	m.RecentRTTs = append(m.RecentRTTs, rttMs)
	if len(m.RecentRTTs) > 10 {
		m.RecentRTTs = m.RecentRTTs[1:]
	}

	sum := 0.0
	for _, r := range m.RecentRTTs {
		sum += r
	}
	m.LocalRTTMs = sum / float64(len(m.RecentRTTs))

	m.RecentLosses = append(m.RecentLosses, false)
	if len(m.RecentLosses) > 10 {
		m.RecentLosses = m.RecentLosses[1:]
	}

	lossCount := 0
	for _, l := range m.RecentLosses {
		if l {
			lossCount++
		}
	}
	m.PacketLossPct = float64(lossCount) / float64(len(m.RecentLosses)) * 100.0
}

func (p *LinkProber) UpdateGroundTruth(nodeID string, gt map[string]interface{}, telem map[string]interface{}) {
	p.mu.Lock()
	defer p.mu.Unlock()
	m, ok := p.nodes[nodeID]
	if !ok {
		m = &NodeMetrics{NodeID: nodeID}
		p.nodes[nodeID] = m
	}
	m.LatestGroundTruth = gt
	m.LatestTelemetry = telem
}
