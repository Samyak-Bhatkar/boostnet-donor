package pairing

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"strings"
	"time"
)

// Session represents an ephemeral or persistent pairing session.
type Session struct {
	MasterSecret string    `json:"secret"`
	LaptopID     string    `json:"id"`
	CreatedAt    time.Time `json:"ts"`
}

// GenerateSession creates a cryptographically secure 256-bit pairing secret.
func GenerateSession(laptopID string) (*Session, error) {
	secretBytes := make([]byte, 32)
	if _, err := io.ReadFull(rand.Reader, secretBytes); err != nil {
		return nil, fmt.Errorf("failed to generate random secret: %w", err)
	}

	return &Session{
		MasterSecret: base64.StdEncoding.EncodeToString(secretBytes),
		LaptopID:     laptopID,
		CreatedAt:    time.Now().UTC(),
	}, nil
}

// QRPayloadJSON returns the JSON payload rendered in the laptop QR code.
func (s *Session) QRPayloadJSON() (string, error) {
	data := map[string]interface{}{
		"id":     s.LaptopID,
		"secret": s.MasterSecret,
		"ts":     s.CreatedAt.Unix(),
	}
	bytes, err := json.Marshal(data)
	if err != nil {
		return "", err
	}
	return string(bytes), nil
}

// DeriveKey derives subkeys using HKDF-SHA256.
func (s *Session) DeriveKey(infoLabel string, length int) []byte {
	ikm := []byte(s.MasterSecret)
	salt := make([]byte, 32) // zero salt per RFC 5869

	// 1. Extract: PRK = HMAC-SHA256(salt, IKM)
	hExtract := hmac.New(sha256.New, salt)
	hExtract.Write(ikm)
	prk := hExtract.Sum(nil)

	// 2. Expand: T(1) = HMAC-SHA256(PRK, info || 0x01)
	hExpand := hmac.New(sha256.New, prk)
	hExpand.Write([]byte(infoLabel))
	hExpand.Write([]byte{0x01})
	t1 := hExpand.Sum(nil)

	if length <= len(t1) {
		return t1[:length]
	}
	return t1
}

func (s *Session) SocksAuthKey() []byte {
	return s.DeriveKey("boostnet-socks-auth-v1", 32)
}

func (s *Session) DiscoveryKey() []byte {
	return s.DeriveKey("boostnet-discovery-hmac-v1", 32)
}

func (s *Session) ControlKey() []byte {
	return s.DeriveKey("boostnet-control-auth-v1", 32)
}

// VisualConfirmationCode returns the 2-digit confirmation token for both screens.
func (s *Session) VisualConfirmationCode() string {
	h := sha256.Sum256([]byte(s.MasterSecret))
	val := (int(h[0])*256 + int(h[1]))%90 + 10
	return fmt.Sprintf("%02d", val)
}

// NumericPIN returns a 6-digit Crockford-style alphanumeric code.
func (s *Session) NumericPIN() string {
	h := sha256.Sum256([]byte(s.MasterSecret))
	const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
	var sb strings.Builder
	for i := 0; i < 6; i++ {
		idx := int(h[i]) % len(alphabet)
		sb.WriteByte(alphabet[idx])
	}
	return sb.String()
}
