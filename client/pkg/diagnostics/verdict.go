package diagnostics

import (
	"fmt"
	"time"
)

type VerdictEngine struct{}

func NewVerdictEngine() *VerdictEngine {
	return &VerdictEngine{}
}

func (e *VerdictEngine) Evaluate(m *NodeMetrics) DiagnosticReport {
	report := DiagnosticReport{
		NodeID:          m.NodeID,
		IP:              m.IP,
		Timestamp:       time.Now().UTC(),
		LocalRTTMs:      m.LocalRTTMs,
		PacketLossPct:   m.PacketLossPct,
		ClockOffsetMs:   float64(m.ClockOffsetNs) / 1e6,
		PhoneMonoNs:     m.PhoneMonoNs,
		CorrectedMonoNs: m.PhoneMonoNs - m.ClockOffsetNs,
		GroundTruth:     m.LatestGroundTruth,
	}

	// Layer 1: LOCAL_LINK Check
	if m.ConsecutiveLosses >= 3 || (time.Since(m.LastPingSuccess) > 6*time.Second && m.LastPingSuccess.Unix() > 0) {
		report.Layer = LayerLocalLink
		report.Category = CategoryUserDeviceState
		report.Verdict = "LINK_LOST_OR_APP_KILLED"
		report.Evidence = []string{
			fmt.Sprintf("Failed 3+ consecutive pings on control port %d", m.CtrlPort),
			fmt.Sprintf("No response from phone for %.1f seconds", time.Since(m.LastPingSuccess).Seconds()),
		}
		report.Remediation = "Local Wi-Fi link lost or Android background service killed by OEM. Check hotspot connection on laptop and ensure BoostNet has battery optimizations disabled."
		return report
	}

	if m.LocalRTTMs > 250.0 || m.PacketLossPct > 20.0 {
		report.Layer = LayerLocalLink
		report.Category = CategoryUserDeviceState
		report.Verdict = "LOCAL_LINK_DEGRADED"
		report.Evidence = []string{
			fmt.Sprintf("High Wi-Fi hotspot latency: %.1fms (threshold: 250ms)", m.LocalRTTMs),
			fmt.Sprintf("High local packet loss: %.1f%% (threshold: 20%%)", m.PacketLossPct),
		}
		report.Remediation = "Local Wi-Fi / hotspot link is congested or weak. Move phone closer to laptop, reduce hotspot interference, or use USB tethering."
		return report
	}

	// Layer 2 & 3: Extract Android Ground Truth
	var netHandleHeld bool = true
	var validated bool = true
	var captivePortal bool = false
	var notVPN bool = true
	var hasIPv4 bool = true
	var restrictBackground string = "DISABLED"

	if m.LatestGroundTruth != nil {
		if cell, ok := m.LatestGroundTruth["cellular"].(map[string]interface{}); ok {
			if v, ok := cell["net_handle_held"].(bool); ok {
				netHandleHeld = v
			}
			if v, ok := cell["validated"].(bool); ok {
				validated = v
			}
			if v, ok := cell["captive_portal"].(bool); ok {
				captivePortal = v
			}
			if v, ok := cell["not_vpn"].(bool); ok {
				notVPN = v
			}
			if v, ok := cell["has_ipv4"].(bool); ok {
				hasIPv4 = v
			}
			if v, ok := cell["restrict_background"].(string); ok {
				restrictBackground = v
			}
		}
	}

	// Check Layer 2: PHONE_OS Restrictions
	if restrictBackground == "ENABLED" {
		report.Layer = LayerPhoneOS
		report.Category = CategoryUserDeviceState
		report.Verdict = "DATA_SAVER_BLOCKING"
		report.Evidence = []string{
			"Android OS ground truth reports restrict_background = ENABLED",
			"App is not whitelisted from Data Saver background socket restrictions",
		}
		report.Remediation = "Do NOT alter scheduling code. Turn off Android Data Saver on the phone, or add BoostNet to 'Unrestricted data access'."
		return report
	}

	if !notVPN {
		report.Layer = LayerPhoneOS
		report.Category = CategoryUserDeviceState
		report.Verdict = "PHONE_VPN_ACTIVE"
		report.Evidence = []string{
			"Android OS ground truth reports not_vpn = false",
			"A third-party VPN is hijacking cellular socket binding",
		}
		report.Remediation = "Disconnect any active VPN or DNS proxy running on the phone."
		return report
	}

	if !netHandleHeld {
		report.Layer = LayerPhoneOS
		report.Category = CategoryAndroidCodeBug
		report.Verdict = "NET_HANDLE_LOST"
		report.Evidence = []string{
			"Android OS reports active cellular network handle is null",
			"NetworkCallback onLost fired or requestNetwork timed out",
		}
		report.Remediation = "Android revoked the cellular network handle. CellularNetworkBinder in Kotlin must re-request the cellular network."
		return report
	}

	if !hasIPv4 {
		report.Layer = LayerPhoneOS
		report.Category = CategoryAndroidCodeBug
		report.Verdict = "IPV6_ONLY"
		report.Evidence = []string{
			"Cellular LinkProperties contain no IPv4 link addresses",
			"Carrier operates on pure IPv6 without 464XLAT CLAT translation",
		}
		report.Remediation = "Carrier is IPv6-only. Ensure laptop client uses domain-based SOCKS5h forwarding rather than IPv4 literals."
		return report
	}

	// Check Layer 3: CELLULAR_PATH
	if captivePortal {
		report.Layer = LayerCellularPath
		report.Category = CategoryUserDeviceState
		report.Verdict = "CAPTIVE_PORTAL_DETECTED"
		report.Evidence = []string{
			"Android OS reports NET_CAPABILITY_CAPTIVE_PORTAL is active",
			"Carrier or Wi-Fi requires browser authentication/terms acceptance",
		}
		report.Remediation = "Open the phone's web browser and complete the carrier or hotspot portal sign-in."
		return report
	}

	if !validated {
		report.Layer = LayerCellularPath
		report.Category = CategoryUserDeviceState
		report.Verdict = "CELL_NOT_VALIDATED"
		report.Evidence = []string{
			"Android OS reports NET_CAPABILITY_VALIDATED is false on cellular network",
			"Public internet reachability failed (possible data cap exhaustion or balance expiry)",
		}
		report.Remediation = "Cellular internet is unvalidated on phone. Check mobile SIM data balance, recharge pack, or mobile data toggle."
		return report
	}

	// Check Sockets & Telemetry
	if m.LatestTelemetry != nil {
		if cellRtt, ok := m.LatestTelemetry["rttMs"].(float64); ok && cellRtt > 900.0 {
			report.Layer = LayerCellularPath
			report.Category = CategoryUserDeviceState
			report.Verdict = "RADIO_STALL_HINT"
			report.Evidence = []string{
				fmt.Sprintf("Cellular RTT is excessively high: %.1fms while local link is %.1fms", cellRtt, m.LocalRTTMs),
				"Probable cell tower congestion or signal fade",
			}
			report.Remediation = "Cell tower congestion detected. Scheduler should temporarily reduce traffic weight to this donor."
			return report
		}
	}

	// All Layers Healthy
	report.Layer = LayerHostEngine
	report.Category = CategoryHostCodeBug
	report.Verdict = "OK"
	report.Evidence = []string{
		fmt.Sprintf("Local link healthy: RTT %.1fms, loss %.1f%%", m.LocalRTTMs, m.PacketLossPct),
		"Android OS healthy: Data Saver off, VPN inactive, cellular bound",
		"Cellular path healthy: Internet validated, IPv4/IPv6 active",
	}
	report.Remediation = "Everything is healthy across Local Link, Phone OS, and Cellular Path."
	return report
}
