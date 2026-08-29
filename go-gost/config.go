package main

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"unicode"
)

const defaultAgentConfigPath = "/etc/gost/config.json"

const defaultGostConfigContent = "{\n  \"services\": []\n}\n"

// Config 配置结构体
type Config struct {
	Addr   string `json:"addr"`
	Secret string `json:"secret"`
	Http   int    `json:"http"`
	Tls    int    `json:"tls"`
	Socks  int    `json:"socks"`
}

// LoadConfig 加载配置文件
func LoadConfig(configPath string) (*Config, error) {
	// 检查文件是否存在
	if _, err := os.Stat(configPath); os.IsNotExist(err) {
		return nil, fmt.Errorf("配置文件不存在: %s", configPath)
	}

	// 读取文件内容
	data, err := os.ReadFile(configPath)
	if err != nil {
		return nil, fmt.Errorf("读取配置文件失败: %v", err)
	}

	// 解析JSON
	var config Config
	if err := json.Unmarshal(data, &config); err != nil {
		return nil, fmt.Errorf("解析配置文件失败: %v", err)
	}

	if err := validateAgentConfig(&config); err != nil {
		return nil, err
	}

	return &config, nil
}

// validateAgentConfig 统一校验文件读取、Compose 首次初始化和环境变量覆盖三条路径。
func validateAgentConfig(config *Config) error {
	if config == nil {
		return fmt.Errorf("Agent 配置不能为空")
	}
	rawAddr := config.Addr
	rawSecret := config.Secret
	config.Addr = strings.TrimSpace(rawAddr)
	config.Secret = strings.TrimSpace(rawSecret)
	if config.Addr == "" {
		return fmt.Errorf("服务器地址不能为空")
	}
	if config.Secret == "" {
		return fmt.Errorf("节点密钥不能为空")
	}
	if strings.IndexFunc(rawSecret, unicode.IsControl) >= 0 {
		return fmt.Errorf("节点密钥不能包含控制字符")
	}
	return validatePanelAddress(rawAddr)
}

// validatePanelAddress 在 Agent 启动阶段拒绝明显无效的面板地址,避免节点
// 读取到错误配置后只在 WebSocket 重连日志里反复失败。兼容历史裸 host:port、
// 带协议的域名以及 IPv4/IPv6 地址。
func validatePanelAddress(value string) error {
	if strings.IndexFunc(value, unicode.IsControl) >= 0 {
		return fmt.Errorf("服务器地址不能包含控制字符")
	}
	address := strings.TrimSpace(value)
	if address == "" {
		return fmt.Errorf("服务器地址不能为空")
	}

	hasScheme := false
	if schemeEnd := strings.Index(address, "://"); schemeEnd >= 0 {
		hasScheme = true
		scheme := strings.ToLower(address[:schemeEnd])
		switch scheme {
		case "http", "https", "ws", "wss":
		default:
			return fmt.Errorf("服务器地址只支持 http://、https://、ws:// 或 wss:// 协议")
		}
		address = address[schemeEnd+3:]
	}
	if cut := strings.IndexAny(address, "/?#"); cut >= 0 {
		if address[cut:] != "/" {
			return fmt.Errorf("服务器地址不支持路径、查询参数或片段,请填写域名或 IP:端口")
		}
		address = address[:cut]
	}
	if address == "" {
		return fmt.Errorf("服务器地址格式无效")
	}

	if strings.HasPrefix(address, "[") {
		end := strings.IndexByte(address, ']')
		if end <= 1 || net.ParseIP(address[1:end]) == nil {
			return fmt.Errorf("IPv6 服务器地址格式无效,请填写 [IPv6]:端口")
		}
		rest := address[end+1:]
		if rest == "" {
			if !hasScheme {
				return fmt.Errorf("裸 IPv6 服务器地址必须包含端口,例如 [2001:db8::1]:6365")
			}
			return nil
		}
		if !strings.HasPrefix(rest, ":") || !validPanelPort(rest[1:]) {
			return fmt.Errorf("服务器地址端口必须在1-65535范围内")
		}
		return nil
	}

	if strings.ContainsAny(address, "[]@") {
		return fmt.Errorf("服务器地址格式无效")
	}

	colonCount := strings.Count(address, ":")
	if colonCount > 1 {
		// 兼容旧配置中的未加方括号 IPv6:port。
		last := strings.LastIndexByte(address, ':')
		if last > 0 && net.ParseIP(address[:last]) != nil && validPanelPort(address[last+1:]) {
			return nil
		}
		if net.ParseIP(address) != nil && hasScheme {
			return nil
		}
		return fmt.Errorf("IPv6 服务器地址格式无效,请填写 [IPv6]:端口")
	}

	if colonCount == 1 {
		parts := strings.SplitN(address, ":", 2)
		if !validPanelHost(parts[0]) {
			return fmt.Errorf("服务器地址主机格式无效")
		}
		if !validPanelPort(parts[1]) {
			return fmt.Errorf("服务器地址端口必须在1-65535范围内")
		}
		return nil
	}

	if !hasScheme {
		return fmt.Errorf("裸服务器地址必须包含端口,例如 example.com:6365")
	}
	if !validPanelHost(address) {
		return fmt.Errorf("服务器地址主机格式无效")
	}
	return nil
}

func validPanelHost(host string) bool {
	if host == "" {
		return false
	}
	for _, r := range host {
		if !(r == '.' || r == '_' || r == '-' || r >= '0' && r <= '9' ||
			r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z') {
			return false
		}
	}
	return true
}

func validPanelPort(port string) bool {
	if port == "" {
		return false
	}
	value, err := strconv.Atoi(port)
	return err == nil && value > 0 && value <= 65535
}

// agentConfigPath resolves the bootstrap configuration used by the WebSocket agent.
func agentConfigPath() string {
	if path := strings.TrimSpace(os.Getenv("TMS_AGENT_CONFIG")); path != "" {
		return path
	}
	if _, err := os.Stat("config.json"); err == nil {
		return "config.json"
	}
	return defaultAgentConfigPath
}

// loadOrCreateAgentConfig keeps bare-metal config files working and bootstraps
// a container config from environment variables when the volume is empty.
func loadOrCreateAgentConfig() (*Config, error) {
	path := agentConfigPath()
	if _, err := os.Stat(path); err == nil {
		addr := os.Getenv("TMS_PANEL_ADDR")
		secret := os.Getenv("TMS_NODE_SECRET")
		config, loadErr := LoadConfig(path)
		if loadErr != nil {
			// Compose 卷可能保留了损坏或旧格式的 config.json。只要两项环境变量
			// 都完整，就用它们重新初始化；裸机没有环境变量时仍返回原始错误。
			if strings.TrimSpace(addr) == "" || strings.TrimSpace(secret) == "" {
				return nil, loadErr
			}
			config = &Config{Addr: addr, Secret: secret}
			if err := validateAgentConfig(config); err != nil {
				return nil, err
			}
			if err := saveAgentConfig(path, config); err != nil {
				return nil, err
			}
			return config, nil
		}

		// Compose 重建容器时 /etc/gost 是持久化 volume,旧配置不能遮住新下发的
		// 面板地址和密钥。裸机部署没有这两个环境变量时继续完全使用原配置。
		if strings.TrimSpace(addr) != "" && strings.TrimSpace(secret) != "" &&
			(config.Addr != strings.TrimSpace(addr) || config.Secret != strings.TrimSpace(secret)) {
			config.Addr = addr
			config.Secret = secret
			if err := validateAgentConfig(config); err != nil {
				return nil, err
			}
			if err := saveAgentConfig(path, config); err != nil {
				return nil, err
			}
		}
		return config, nil
	} else if !os.IsNotExist(err) {
		return nil, fmt.Errorf("检查配置文件失败: %v", err)
	}

	addr := os.Getenv("TMS_PANEL_ADDR")
	secret := os.Getenv("TMS_NODE_SECRET")
	if strings.TrimSpace(addr) == "" || strings.TrimSpace(secret) == "" {
		return nil, fmt.Errorf("配置文件不存在: %s；请设置 TMS_PANEL_ADDR 和 TMS_NODE_SECRET", path)
	}

	config := &Config{Addr: addr, Secret: secret}
	if err := validateAgentConfig(config); err != nil {
		return nil, err
	}
	if err := saveAgentConfig(path, config); err != nil {
		return nil, err
	}
	return config, nil
}

// ensureGostConfig prepares the file consumed by go-gost's parser. The Agent
// bootstrap config is intentionally separate from the proxy configuration.
// Keep existing gost.json/gost.yaml files intact for bare-metal upgrades, but
// create an empty JSON config for a fresh Docker volume.
func ensureGostConfig() (string, error) {
	if path := strings.TrimSpace(cfgFile); path != "" {
		return path, nil
	}

	dir := filepath.Dir(agentConfigPath())
	for _, name := range []string{"gost.json", "gost.yaml"} {
		path := filepath.Join(dir, name)
		if _, err := os.Stat(path); err == nil {
			return path, nil
		} else if !os.IsNotExist(err) {
			return "", fmt.Errorf("检查 GOST 配置失败: %v", err)
		}
	}

	path := filepath.Join(dir, "gost.json")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", fmt.Errorf("创建 GOST 配置目录失败: %v", err)
	}
	if err := os.WriteFile(path, []byte(defaultGostConfigContent), 0o600); err != nil {
		return "", fmt.Errorf("创建 GOST 配置失败: %v", err)
	}
	return path, nil
}

func isInlineGostConfig(value string) bool {
	value = strings.TrimSpace(value)
	return strings.HasPrefix(value, "{") && strings.HasSuffix(value, "}")
}

// saveAgentConfig 用临时文件替换配置,避免容器重启时读到半个 JSON。
func saveAgentConfig(path string, config *Config) error {
	data, err := json.MarshalIndent(config, "", "  ")
	if err != nil {
		return fmt.Errorf("生成 Agent 配置失败: %v", err)
	}
	if dir := filepath.Dir(path); dir != "." {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			return fmt.Errorf("创建配置目录失败: %v", err)
		}
	}
	tmpPath := path + ".tmp"
	if err := os.WriteFile(tmpPath, data, 0o600); err != nil {
		return fmt.Errorf("写入临时 Agent 配置失败: %v", err)
	}
	if err := os.Rename(tmpPath, path); err != nil {
		_ = os.Remove(tmpPath)
		return fmt.Errorf("替换 Agent 配置失败: %v", err)
	}
	return nil
}
