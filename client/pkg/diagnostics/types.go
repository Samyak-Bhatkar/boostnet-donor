package diagnostics

import "time"

type Layer string

const (
	LayerLocalLink    Layer = "LOCAL_LINK"
	LayerPhoneOS      Layer = "PHONE_OS"
	LayerCellularPath Layer = "CELLULAR_PATH"
	LayerHostEngine   Layer = "HOST_ENGINE"
)

type Category string

const (
	CategoryHostCodeBug     Category = "CATEGORY_HOST_CODE_BUG"
	CategoryAndroidCodeBug  Category = "CATEGORY_ANDROID_CODE_BUG"
	CategoryUserDeviceState Category = "CATEGORY_USER_DEVICE_STATE"
)

type DiagnosticReport struct {
	NodeID          string                 `json:"node_id"`
	IP              string                 `json:"ip"`
	Timestamp       time.Time              `json:"timestamp"`
	Verdict         string                 `json:"verdict"`
	Layer           Layer                  `json:"layer"`
	Category        Category               `json:"category"`
	LocalRTTMs      float64                `json:"local_rtt_ms"`
	PacketLossPct   float64                `json:"packet_loss_pct"`
	ClockOffsetMs   float64                `json:"clock_offset_ms"`
	PhoneMonoNs     int64                  `json:"phone_mono_ns"`
	CorrectedMonoNs int64                  `json:"corrected_mono_ns"`
	GroundTruth     map[string]interface{} `json:"ground_truth,omitempty"`
	Evidence        []string               `json:"evidence"`
	Remediation     string                 `json:"remediation"`
}

type FlightLogRecord struct {
	Timestamp     string                 `json:"timestamp"`
	LaptopMonoNs  int64                  `json:"laptop_mono_ns"`
	PhoneMonoNs   int64                  `json:"phone_mono_ns,omitempty"`
	ClockOffsetNs int64                  `json:"clock_offset_ns,omitempty"`
	NodeID        string                 `json:"node_id"`
	TraceID       string                 `json:"trace_id,omitempty"`
	Layer         Layer                  `json:"layer"`
	Event         string                 `json:"event"`
	Verdict       string                 `json:"verdict,omitempty"`
	Category      Category               `json:"category,omitempty"`
	Details       map[string]interface{} `json:"details,omitempty"`
}
