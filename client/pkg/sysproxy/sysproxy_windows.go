//go:build windows
// +build windows

package sysproxy

import (
	"fmt"
	"log"
	"syscall"

	"golang.org/x/sys/windows/registry"
)

var (
	wininet                = syscall.NewLazyDLL("wininet.dll")
	procInternetSetOptionW = wininet.NewProc("InternetSetOptionW")
)

const (
	INTERNET_OPTION_SETTINGS_CHANGED = 39
	INTERNET_OPTION_REFRESH          = 42
)

// CheckAndSelfHealStartup inspects Windows registry for lingering proxy settings from abnormal crash.
func CheckAndSelfHealStartup() {
	key, err := openInternetSettingsKey()
	if err != nil {
		return
	}
	defer key.Close()

	server, _, err := key.GetStringValue("ProxyServer")
	if err == nil && server == "127.0.0.1:8080" {
		log.Println("[SysProxy] Detected lingering proxy from previous ungraceful shutdown. Self-healing...")
		_ = DisableProxy()
	}
}

// EnableProxy configures Windows system proxy to point to 127.0.0.1:8080 with bypass list.
func EnableProxy(proxyAddr string) error {
	key, err := openInternetSettingsKey()
	if err != nil {
		return err
	}
	defer key.Close()

	bypass := "<local>;127.*;10.*;172.16.*;192.168.*"

	if err := key.SetDWordValue("ProxyEnable", 1); err != nil {
		return fmt.Errorf("failed to enable proxy: %w", err)
	}
	if err := key.SetStringValue("ProxyServer", proxyAddr); err != nil {
		return fmt.Errorf("failed to set proxy server: %w", err)
	}
	if err := key.SetStringValue("ProxyOverride", bypass); err != nil {
		return fmt.Errorf("failed to set proxy override: %w", err)
	}

	notifyWinInet()
	log.Printf("[SysProxy] Windows system proxy enabled -> %s (Bypass: %s)", proxyAddr, bypass)
	return nil
}

// DisableProxy restores direct internet.
func DisableProxy() error {
	key, err := openInternetSettingsKey()
	if err != nil {
		return err
	}
	defer key.Close()

	_ = key.SetDWordValue("ProxyEnable", 0)
	notifyWinInet()
	log.Println("[SysProxy] Windows system proxy disabled. Direct internet restored.")
	return nil
}

func notifyWinInet() {
	_, _, _ = procInternetSetOptionW.Call(0, INTERNET_OPTION_SETTINGS_CHANGED, 0, 0)
	_, _, _ = procInternetSetOptionW.Call(0, INTERNET_OPTION_REFRESH, 0, 0)
}

func openInternetSettingsKey() (registry.Key, error) {
	return registry.OpenKey(registry.CURRENT_USER, `Software\Microsoft\Windows\CurrentVersion\Internet Settings`, registry.ALL_ACCESS)
}
