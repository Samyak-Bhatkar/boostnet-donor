//go:build windows
// +build windows

package sysproxy

import (
	"fmt"
	"log"
	"syscall"
	"unsafe"
)

var (
	wininet                 = syscall.NewLazyDLL("wininet.dll")
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
	defer syscall.RegCloseKey(key)

	server, err := readStringValue(key, "ProxyServer")
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
	defer syscall.RegCloseKey(key)

	bypass := "<local>;127.*;10.*;172.16.*;192.168.*"

	if err := writeDwordValue(key, "ProxyEnable", 1); err != nil {
		return fmt.Errorf("failed to enable proxy: %w", err)
	}
	if err := writeStringValue(key, "ProxyServer", proxyAddr); err != nil {
		return fmt.Errorf("failed to set proxy server: %w", err)
	}
	if err := writeStringValue(key, "ProxyOverride", bypass); err != nil {
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
	defer syscall.RegCloseKey(key)

	_ = writeDwordValue(key, "ProxyEnable", 0)
	notifyWinInet()
	log.Println("[SysProxy] Windows system proxy disabled. Direct internet restored.")
	return nil
}

func notifyWinInet() {
	_, _, _ = procInternetSetOptionW.Call(0, INTERNET_OPTION_SETTINGS_CHANGED, 0, 0)
	_, _, _ = procInternetSetOptionW.Call(0, INTERNET_OPTION_REFRESH, 0, 0)
}

func openInternetSettingsKey() (syscall.Handle, error) {
	var key syscall.Handle
	subKey, _ := syscall.UTF16PtrFromString(`Software\Microsoft\Windows\CurrentVersion\Internet Settings`)
	err := syscall.RegOpenKeyEx(syscall.HKEY_CURRENT_USER, subKey, 0, syscall.KEY_ALL_ACCESS, &key)
	return key, err
}

func writeDwordValue(key syscall.Handle, valueName string, val uint32) error {
	namePtr, _ := syscall.UTF16PtrFromString(valueName)
	return syscall.RegSetValueEx(key, namePtr, 0, syscall.REG_DWORD, (*byte)(unsafe.Pointer(&val)), 4)
}

func writeStringValue(key syscall.Handle, valueName string, val string) error {
	namePtr, _ := syscall.UTF16PtrFromString(valueName)
	valPtr, _ := syscall.UTF16FromString(val)
	return syscall.RegSetValueEx(key, namePtr, 0, syscall.REG_SZ, (*byte)(unsafe.Pointer(&valPtr[0])), uint32(len(valPtr)*2))
}

func readStringValue(key syscall.Handle, valueName string) (string, error) {
	namePtr, _ := syscall.UTF16PtrFromString(valueName)
	var typ uint32
	var bufSize uint32 = 512
	buf := make([]uint16, bufSize)

	err := syscall.RegQueryValueEx(key, namePtr, nil, &typ, (*byte)(unsafe.Pointer(&buf[0])), &bufSize)
	if err != nil {
		return "", err
	}
	return syscall.UTF16ToString(buf), nil
}
