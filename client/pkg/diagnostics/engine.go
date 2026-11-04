package diagnostics

import (
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"boostnet-client/pkg/discovery"
)

type Engine struct {
	mu       sync.RWMutex
	pool     *discovery.Pool
	recorder *FlightRecorder
	prober   *LinkProber
	verdict  *VerdictEngine
}

func NewEngine(pool *discovery.Pool, controlKey []byte) (*Engine, error) {
	recorder, err := NewFlightRecorder("")
	if err != nil {
		return nil, fmt.Errorf("failed to init flight recorder: %w", err)
	}

	prober := NewLinkProber(pool, controlKey, recorder)
	verdict := NewVerdictEngine()

	eng := &Engine{
		pool:     pool,
		recorder: recorder,
		prober:   prober,
		verdict:  verdict,
	}

	prober.Start()
	eng.startPeriodicFlightLogger()

	return eng, nil
}

func (e *Engine) Stop() {
	e.prober.Stop()
}

func (e *Engine) DiagnoseAll() []DiagnosticReport {
	metrics := e.prober.GetAllMetrics()
	var reports []DiagnosticReport
	for _, m := range metrics {
		report := e.verdict.Evaluate(m)
		reports = append(reports, report)
	}
	return reports
}

func (e *Engine) DiagnoseJSON() (string, error) {
	reports := e.DiagnoseAll()
	summary := map[string]interface{}{
		"system":        "BoostNet 2.0 Deterministic Diagnostic Oracle",
		"timestamp":     time.Now().UTC().Format(time.RFC3339),
		"active_donors": len(reports),
		"reports":       reports,
	}

	data, err := json.MarshalIndent(summary, "", "  ")
	if err != nil {
		return "", err
	}
	return string(data), nil
}

func (e *Engine) RecordFlightEvent(layer Layer, event string, nodeID string, traceID string, details map[string]interface{}) {
	var phoneMonoNs int64
	var offsetNs int64
	if m := e.prober.GetNodeMetrics(nodeID); m != nil {
		phoneMonoNs = m.PhoneMonoNs
		offsetNs = m.ClockOffsetNs
	}

	rec := FlightLogRecord{
		Timestamp:     time.Now().UTC().Format(time.RFC3339Nano),
		LaptopMonoNs:  time.Now().UnixNano(),
		PhoneMonoNs:   phoneMonoNs,
		ClockOffsetNs: offsetNs,
		NodeID:        nodeID,
		TraceID:       traceID,
		Layer:         layer,
		Event:         event,
		Details:       details,
	}
	_ = e.recorder.Record(rec)
}

func (e *Engine) startPeriodicFlightLogger() {
	go func() {
		ticker := time.NewTicker(3 * time.Second)
		defer ticker.Stop()

		for range ticker.C {
			reports := e.DiagnoseAll()
			for _, r := range reports {
				_ = e.recorder.Record(FlightLogRecord{
					Timestamp:     r.Timestamp.Format(time.RFC3339Nano),
					LaptopMonoNs:  time.Now().UnixNano(),
					PhoneMonoNs:   r.PhoneMonoNs,
					ClockOffsetNs: int64(r.ClockOffsetMs * 1e6),
					NodeID:        r.NodeID,
					Layer:         r.Layer,
					Event:         "PERIODIC_HEALTH_EVAL",
					Verdict:       r.Verdict,
					Category:      r.Category,
					Details: map[string]interface{}{
						"local_rtt_ms":    r.LocalRTTMs,
						"packet_loss_pct": r.PacketLossPct,
						"evidence":        r.Evidence,
					},
				})
			}
		}
	}()
}
