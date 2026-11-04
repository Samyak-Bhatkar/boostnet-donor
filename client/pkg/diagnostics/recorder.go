package diagnostics

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"
)

type FlightRecorder struct {
	mu       sync.Mutex
	filePath string
}

func NewFlightRecorder(filePath string) (*FlightRecorder, error) {
	if filePath == "" {
		filePath = filepath.Join("logs", "boostnet_flight_recorder.jsonl")
	}

	dir := filepath.Dir(filePath)
	if err := os.MkdirAll(dir, 0755); err != nil {
		return nil, fmt.Errorf("failed to create logs directory: %w", err)
	}

	return &FlightRecorder{
		filePath: filePath,
	}, nil
}

func (r *FlightRecorder) Record(record FlightLogRecord) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	if record.Timestamp == "" {
		record.Timestamp = time.Now().UTC().Format(time.RFC3339Nano)
	}

	bytes, err := json.Marshal(record)
	if err != nil {
		return err
	}

	f, err := os.OpenFile(r.filePath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0644)
	if err != nil {
		return err
	}
	defer f.Close()

	if _, err := f.Write(append(bytes, '\n')); err != nil {
		return err
	}
	return nil
}

func (r *FlightRecorder) ReadLastRecords(limit int) ([]FlightLogRecord, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	f, err := os.Open(r.filePath)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, nil
		}
		return nil, err
	}
	defer f.Close()

	var records []FlightLogRecord
	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		line := scanner.Bytes()
		if len(line) == 0 {
			continue
		}
		var rec FlightLogRecord
		if err := json.Unmarshal(line, &rec); err == nil {
			records = append(records, rec)
		}
	}

	if len(records) > limit {
		records = records[len(records)-limit:]
	}
	return records, nil
}
