//go:build !windows
// +build !windows

package sysproxy

import "log"

// CheckAndSelfHealStartup is a no-op on non-Windows platforms.
func CheckAndSelfHealStartup() {
}

// EnableProxy logs instruction or sets platform proxy.
func EnableProxy(proxyAddr string) error {
	log.Printf("[SysProxy] System proxy configuration available. Set HTTP proxy to: %s", proxyAddr)
	return nil
}

// DisableProxy restores direct connection.
func DisableProxy() error {
	log.Println("[SysProxy] System proxy cleared.")
	return nil
}
