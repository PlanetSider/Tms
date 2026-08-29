package socket

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/go-gost/x/config"
)

func saveConfig() {
	file := gostConfigPath()

	f, err := os.Create(file)
	if err != nil {
		return
	}
	defer f.Close()

	format := "json"
	switch strings.ToLower(filepath.Ext(file)) {
	case ".yaml", ".yml":
		format = "yaml"
	}
	if err := config.Global().Write(f, format); err != nil {

		return
	}

	return
}

func gostConfigPath() string {
	if path := strings.TrimSpace(os.Getenv("TMS_GOST_CONFIG")); path != "" {
		return path
	}
	return filepath.Join(filepath.Dir(agentConfigPath()), "gost.json")
}

func agentConfigPath() string {
	if path := strings.TrimSpace(os.Getenv("TMS_AGENT_CONFIG")); path != "" {
		return path
	}
	if _, err := os.Stat("config.json"); err == nil {
		return "config.json"
	}
	return "/etc/gost/config.json"
}

func readProtocolConfig() (httpVal, tlsVal, socksVal int) {
	data, err := os.ReadFile(agentConfigPath())
	if err != nil {
		return 0, 0, 0
	}
	var cfg struct {
	Http  int `json:"http"`
	Tls   int `json:"tls"`
	Socks int `json:"socks"`
	}
	if err := json.Unmarshal(data, &cfg); err != nil {
		return 0, 0, 0
	}
	return cfg.Http, cfg.Tls, cfg.Socks
}

func updateProtocolConfig(httpVal, tlsVal, socksVal int) error {
	path := agentConfigPath()
	data, err := os.ReadFile(path)
	if err != nil {
		return fmt.Errorf("读取 Agent 配置失败: %v", err)
	}
	var cfg map[string]json.RawMessage
	if err := json.Unmarshal(data, &cfg); err != nil {
		return fmt.Errorf("解析 Agent 配置失败: %v", err)
	}
	if cfg == nil {
		return fmt.Errorf("解析 Agent 配置失败: 配置内容为空")
	}
	for key, value := range map[string]int{
		"http":  httpVal,
		"tls":   tlsVal,
		"socks": socksVal,
	} {
		encoded, err := json.Marshal(value)
		if err != nil {
			return fmt.Errorf("编码协议配置失败: %v", err)
		}
		cfg[key] = encoded
	}
	updated, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return err
	}

	// 临时文件必须和目标文件处于同一目录,这样 Rename 才是原子的。
	dir := filepath.Dir(path)
	tmp, err := os.CreateTemp(dir, ".config.json.tmp-*")
	if err != nil {
		return fmt.Errorf("创建 Agent 配置临时文件失败: %v", err)
	}
	tmpPath := tmp.Name()
	defer os.Remove(tmpPath)

	if err := tmp.Chmod(0o600); err != nil {
		_ = tmp.Close()
		return fmt.Errorf("设置 Agent 配置临时文件权限失败: %v", err)
	}
	if _, err := tmp.Write(updated); err != nil {
		_ = tmp.Close()
		return fmt.Errorf("写入 Agent 配置临时文件失败: %v", err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("关闭 Agent 配置临时文件失败: %v", err)
	}
	if err := os.Rename(tmpPath, path); err != nil {
		return fmt.Errorf("替换 Agent 配置失败: %v", err)
	}
	return nil
}
