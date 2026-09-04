package socket

// 合体面板(协议+限速)· 节点端 sing-box 管理模块
// 面板通过 WebSocket 下发 SetSingboxConfig 命令,节点负责:
//   1) 确保 sing-box 外部二进制已安装(Compose 镜像默认已内置 v1.13.12);
//   2) 写入面板生成的完整 sing-box 配置 JSON;
//   3) 由 Agent 直接管理 sing-box 子进程。
// sing-box 只在 127.0.0.1 监听,公网口由 gost 转发占用并限速。

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"context"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"syscall"
	"time"
)

const (
	installDir     = "/etc/gost"
	singboxVersion = "1.13.12"
	defaultSingbox = "/etc/gost/sing-box"
)

// 串行化配置写入 + 重载,避免并发下发时打架
var singboxMu sync.Mutex

var (
	singboxProcessMu   sync.Mutex
	singboxProcess     *exec.Cmd
	singboxProcessDone chan struct{}
	singboxDesired     bool
	// singboxShutdown 只在 Agent 退出时置为 true,防止停止流程和启动恢复并发时
	// 又把 sing-box 拉起来。普通的配置重载不会设置这个标记。
	singboxShutdown bool

	singboxRestartMu       sync.Mutex
	singboxRestartFailures int
)

// sing-box 的安装进度。保留这组状态是为了兼容旧版裸机节点；Compose 镜像已预装
// sing-box,新节点通常只会在镜像未更新或显式开启运行时下载时进入安装状态。
var (
	singboxStateMu    sync.Mutex
	singboxInstalling bool
	// 上一次安装失败的原因。装成功或重新开始安装时清空 ——
	// 留着旧错误会让已经修好的机器一直显示红字。
	singboxInstallErr string
)

func setSingboxInstalling(v bool) {
	singboxStateMu.Lock()
	singboxInstalling = v
	if v {
		singboxInstallErr = ""
	}
	singboxStateMu.Unlock()
}

func setSingboxInstallErr(msg string) {
	singboxStateMu.Lock()
	singboxInstallErr = msg
	singboxStateMu.Unlock()
}

// SingboxProgress 给上报用:是否正在安装、以及上次安装失败的原因(没有则空)
func SingboxProgress() (installing bool, lastErr string) {
	singboxStateMu.Lock()
	defer singboxStateMu.Unlock()
	return singboxInstalling, singboxInstallErr
}

// SetSingboxConfig 命令下发的数据:面板给完整 sing-box 配置 + 可选国内下载镜像
type singboxConfigRequest struct {
	Config json.RawMessage `json:"config"`           // 完整 sing-box 配置(log/inbounds/outbounds…)
	Mirror string          `json:"mirror,omitempty"` // 国内 GitHub 镜像前缀(如 https://ghfast.top/),可空
}

func singboxBinPath() string {
	if path := strings.TrimSpace(os.Getenv("SINGBOX_BIN")); path != "" {
		return path
	}
	return defaultSingbox
}

func singboxExecPath() string {
	path := singboxBinPath()
	if strings.TrimSpace(os.Getenv("SINGBOX_BIN")) == "" {
		if _, err := os.Stat(path); os.IsNotExist(err) {
			legacy := filepath.Join(installDir, "sing-box")
			if fi, legacyErr := os.Stat(legacy); legacyErr == nil && fi.Mode().IsRegular() && fi.Size() > 0 {
				return legacy
			}
		}
	}
	return path
}

func singboxConfigPath() string { return filepath.Join(installDir, "sing-box.json") }
func singboxEnabledPath() string { return filepath.Join(installDir, "sing-box.enabled") }
func singboxDisabledPath() string { return filepath.Join(installDir, "sing-box.disabled") }

// hasSingboxConfig 判断卷内是否已经有协议配置。新节点只有面板下发协议后才会启动 sing-box。
func hasSingboxConfig() bool {
	fi, err := os.Stat(singboxConfigPath())
	return err == nil && fi.Mode().IsRegular() && fi.Size() > 0
}

// persistSingboxEnabled 保存 sing-box 的运行意图。标记文件放在 /etc/gost 卷内,
// 这样容器重建后 Agent 仍能判断是应该恢复协议还是保持停用。
func persistSingboxEnabled(enabled bool) error {
	if err := os.MkdirAll(installDir, 0o755); err != nil {
		return fmt.Errorf("创建 sing-box 状态目录失败: %v", err)
	}

	if enabled {
		if err := os.WriteFile(singboxEnabledPath(), []byte("enabled\n"), 0o600); err != nil {
			return fmt.Errorf("保存 sing-box 启用状态失败: %v", err)
		}
		if err := os.Remove(singboxDisabledPath()); err != nil && !os.IsNotExist(err) {
			return fmt.Errorf("清理 sing-box 停用状态失败: %v", err)
		}
		return nil
	}

	if err := os.WriteFile(singboxDisabledPath(), []byte("disabled\n"), 0o600); err != nil {
		return fmt.Errorf("保存 sing-box 停用状态失败: %v", err)
	}
	if err := os.Remove(singboxEnabledPath()); err != nil && !os.IsNotExist(err) {
		return fmt.Errorf("清理 sing-box 启用状态失败: %v", err)
	}
	return nil
}

// shouldRestoreSingbox 对旧版裸机节点兼容:有配置且没有 disabled 标记就恢复。
// 新建的 Compose 节点没有 sing-box.json,因此不会误启动空协议服务。
func shouldRestoreSingbox() bool {
	if !hasSingboxConfig() {
		return false
	}
	_, err := os.Stat(singboxDisabledPath())
	return os.IsNotExist(err)
}

// ---- 命令处理(在 routeCommand 里被调用)----

func (w *WebSocketReporter) handleSetSingboxConfig(data interface{}) error {
	singboxMu.Lock()
	defer singboxMu.Unlock()

	jsonData, err := json.Marshal(data)
	if err != nil {
		return fmt.Errorf("序列化 sing-box 配置失败: %v", err)
	}
	var req singboxConfigRequest
	if err := json.Unmarshal(jsonData, &req); err != nil {
		return fmt.Errorf("解析 sing-box 配置失败: %v", err)
	}
	if len(req.Config) == 0 {
		return fmt.Errorf("sing-box 配置为空")
	}

	if err := ensureSingboxInstalled(req.Mirror); err != nil {
		return err
	}
	if err := ensureSelfCert(); err != nil { // Hysteria2/TUIC/AnyTLS 用的自签证书,没有则生成
		return err
	}
	if err := writeSingboxConfig(req.Config); err != nil {
		return err
	}
	if err := reloadSingbox(); err != nil {
		return err
	}
	return nil
}

func selfCertPath() string { return filepath.Join(installDir, "certs", "self.crt") }
func selfKeyPath() string  { return filepath.Join(installDir, "certs", "self.key") }

// ensureSelfCert 没有自签证书就用 sing-box 生成一张(Hysteria2/TUIC/AnyTLS 共用,客户端用 insecure=1)。
// 面板侧配置固定引用 /etc/gost/certs/self.crt|self.key。
func ensureSelfCert() error {
	crt := selfCertPath()
	key := selfKeyPath()
	if certInfo, certErr := os.Stat(crt); certErr == nil && certInfo.Mode().IsRegular() && certInfo.Size() > 0 {
		if keyInfo, keyErr := os.Stat(key); keyErr == nil && keyInfo.Mode().IsRegular() && keyInfo.Size() > 0 {
			return nil
		}
	}
	if err := os.MkdirAll(filepath.Dir(crt), 0o755); err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	// sing-box 1.13.x 要求 server_name 使用位置参数，不支持 --domain；--months
	// 是可选有效期参数。证书写入持久化目录后不会重复生成，因此使用长期有效期。
	out, err := exec.CommandContext(ctx, singboxExecPath(), "generate", "tls-keypair", "www.bing.com", "--months", "120").CombinedOutput()
	if err != nil {
		return fmt.Errorf("生成自签证书失败: %v, %s", err, string(out))
	}
	keyPem, certPem := splitPem(string(out))
	if keyPem == "" || certPem == "" {
		return fmt.Errorf("解析自签证书失败: %s", string(out))
	}
	if err := os.WriteFile(key, []byte(keyPem), 0o600); err != nil {
		return err
	}
	if err := os.WriteFile(crt, []byte(certPem), 0o644); err != nil {
		return err
	}
	return nil
}

// splitPem 从 `sing-box generate tls-keypair` 输出里拆出私钥块和证书块。
// 私钥类型在不同版本/实现中可能是 PRIVATE KEY 或 EC PRIVATE KEY,
// 因此不能依赖固定的 PEM 头字符串。
func splitPem(out string) (key, cert string) {
	rest := []byte(out)
	for len(rest) > 0 {
		// pem.Decode 只会从它找到的 PEM 起点开始解码。sing-box 的输出
		// 可能在 PEM 前带提示文字,所以先跳过普通文本再尝试解析。
		start := bytes.Index(rest, []byte("-----BEGIN "))
		if start < 0 {
			break
		}
		candidate := rest[start:]
		block, next := pem.Decode(candidate)
		if block == nil {
			// 遇到损坏或不完整的 PEM 标记时继续找后面的标记,避免一段
			// 普通输出阻断后续有效的证书/私钥块。
			rest = candidate[len("-----BEGIN "):]
			continue
		}
		encoded := string(pem.EncodeToMemory(block))
		switch {
		case key == "" && strings.Contains(strings.ToUpper(block.Type), "PRIVATE KEY"):
			key = encoded
		case cert == "" && strings.EqualFold(block.Type, "CERTIFICATE"):
			cert = encoded
		}
		rest = next
	}
	return key, cert
}

func (w *WebSocketReporter) handleDeleteSingbox(data interface{}) error {
	singboxMu.Lock()
	defer singboxMu.Unlock()
	if err := persistSingboxEnabled(false); err != nil {
		return err
	}
	return stopSingbox()
}

// handleGenerateRealityKeypair 用 sing-box 生成 Reality 密钥对(比在后端手搓 x25519 可靠)
// 返回 {"privateKey": "...", "publicKey": "..."},面板存起来:私钥入服务端配置、公钥进客户端链接。
func (w *WebSocketReporter) handleGenerateRealityKeypair(data interface{}) (map[string]string, error) {
	fmt.Println("🔑 [reality] 收到,等 singboxMu 锁...")
	singboxMu.Lock()
	defer singboxMu.Unlock()
	fmt.Println("🔑 [reality] 已拿锁,检查 sing-box 是否就绪...")

	// 请求可带 mirror,用于兼容旧版节点首次下载 sing-box 二进制
	var req struct {
		Mirror string `json:"mirror,omitempty"`
	}
	if data != nil {
		if b, err := json.Marshal(data); err == nil {
			_ = json.Unmarshal(b, &req)
		}
	}
	if err := ensureSingboxInstalled(req.Mirror); err != nil {
		fmt.Printf("🔑 [reality] ensureSingboxInstalled 失败: %v\n", err)
		return nil, err
	}
	fmt.Println("🔑 [reality] sing-box 就绪, exec generate reality-keypair...")

	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	out, err := exec.CommandContext(ctx, singboxExecPath(), "generate", "reality-keypair").CombinedOutput()
	fmt.Printf("🔑 [reality] exec 返回 err=%v out=%q\n", err, string(out))
	if err != nil {
		return nil, fmt.Errorf("生成 reality 密钥失败: %v, %s", err, string(out))
	}
	priv, pub := parseRealityKeypair(string(out))
	if priv == "" || pub == "" {
		return nil, fmt.Errorf("解析 reality 密钥失败: %s", string(out))
	}
	fmt.Printf("🔑 [reality] 成功,priv=%d pub=%d 字符\n", len(priv), len(pub))
	return map[string]string{"privateKey": priv, "publicKey": pub}, nil
}

// parseRealityKeypair 解析 `sing-box generate reality-keypair` 的输出:
//   PrivateKey: xxxx
//   PublicKey: yyyy
func parseRealityKeypair(out string) (priv, pub string) {
	for _, line := range strings.Split(out, "\n") {
		parts := strings.SplitN(line, ":", 2)
		if len(parts) != 2 {
			continue
		}
		switch strings.ToLower(strings.TrimSpace(parts[0])) {
		case "privatekey":
			priv = strings.TrimSpace(parts[1])
		case "publickey":
			pub = strings.TrimSpace(parts[1])
		}
	}
	return priv, pub
}

// ---- 安装 / 配置 / 服务管理 ----

// ensureSingboxInstalled 兼容旧版节点:二进制不存在时下载指定版本并解压
func ensureSingboxInstalled(mirror string) error {
	bin := singboxExecPath()
	if fi, err := os.Stat(bin); err == nil && fi.Mode().IsRegular() && fi.Size() > 0 {
		setSingboxInstallErr("")
		return nil
	}
	if strings.EqualFold(strings.TrimSpace(os.Getenv("SINGBOX_RUNTIME_DOWNLOAD")), "false") {
		msg := fmt.Sprintf("sing-box 未安装: %s；SINGBOX_RUNTIME_DOWNLOAD=false,不会运行时下载", bin)
		setSingboxInstallErr(msg)
		return fmt.Errorf("%s", msg)
	}

	// 走到这说明二进制不在,真要下载了 —— 从这一刻起面板显示「安装中」
	setSingboxInstalling(true)
	defer setSingboxInstalling(false)
	if err := os.MkdirAll(filepath.Dir(bin), 0o755); err != nil {
		msg := fmt.Sprintf("创建 sing-box 目录失败: %v", err)
		setSingboxInstallErr(msg)
		return fmt.Errorf("%s", msg)
	}

	// 下载和解压当成一件事:任一步失败就换下一个源,
	// 免得国内机留下半个包却只报"解压失败",让人以为是归档坏了
	tmp := filepath.Join(installDir, "sing-box.tar.gz")
	var lastErr error
	for _, url := range singboxDownloadURLs(mirror) {
		if err := downloadFile(url, tmp); err != nil {
			os.Remove(tmp)
			lastErr = fmt.Errorf("%s: %v", url, err)
			fmt.Printf("⚠️ 下载 sing-box 失败,换下一个源: %v\n", lastErr)
			continue
		}
		if err := extractSingboxBinary(tmp, bin); err != nil {
			os.Remove(tmp)
			lastErr = fmt.Errorf("%s 解压失败: %v", url, err)
			fmt.Printf("⚠️ %v,换下一个源\n", lastErr)
			continue
		}
		os.Remove(tmp)
		if err := os.Chmod(bin, 0o755); err != nil {
			msg := fmt.Sprintf("给 sing-box 加执行权限失败: %v", err)
			setSingboxInstallErr(msg)
			return fmt.Errorf("%s", msg)
		}
		setSingboxInstallErr("")
		fmt.Printf("✅ sing-box %s 安装完成(源: %s)\n", singboxVersion, url)
		return nil
	}
	msg := fmt.Sprintf("所有下载源都失败,最后一个 %v", lastErr)
	// 记下来报给面板:否则那台机只会显示「没装上」,而为什么装不上
	// 旧版裸机只能上机器查系统日志；Compose 部署请直接查看容器日志。
	setSingboxInstallErr(msg)
	return fmt.Errorf("%s", msg)
}

// GitHub 加速镜像,给国内机器兜底 —— 拼在完整 github 地址前面即可。
var singboxMirrors = []string{
	"https://ghfast.top/",
	"https://gh-proxy.com/",
	"https://ghproxy.net/",
}

// singboxDownloadURLs 按优先级列出候选下载地址:直连排第一(境外机秒过),
// 连不上或下到一半断流就顺着镜像往下换。
//
// 光靠调用方传 mirror 不够 —— 后台预装那条路径压根没传,国内机必然
// context deadline exceeded,所以兜底放在这里,谁调用都能自愈。
func singboxDownloadURLs(mirror string) []string {
	asset := fmt.Sprintf("sing-box-%s-linux-%s.tar.gz", singboxVersion, runtime.GOARCH)
	origin := fmt.Sprintf("https://github.com/SagerNet/sing-box/releases/download/v%s/%s", singboxVersion, asset)

	urls := make([]string, 0, len(singboxMirrors)+2)
	if mirror != "" {
		urls = append(urls, mirror+origin)
	}
	urls = append(urls, origin)
	for _, m := range singboxMirrors {
		if m != mirror {
			urls = append(urls, m+origin)
		}
	}
	return urls
}

func writeSingboxConfig(cfg json.RawMessage) error {
	if err := os.MkdirAll(installDir, 0o755); err != nil {
		return fmt.Errorf("创建 sing-box 配置目录失败: %v", err)
	}

	// 先校验临时文件,只有新配置可用时才替换旧配置,避免一次错误下发让节点
	// 既无法 reload,又在容器重启后持续加载坏配置。
	tmpPath := singboxConfigPath() + ".tmp"
	if err := os.WriteFile(tmpPath, cfg, 0o600); err != nil {
		return fmt.Errorf("写 sing-box 临时配置失败: %v", err)
	}
	defer os.Remove(tmpPath)
	if err := checkSingboxConfigPath(tmpPath); err != nil {
		setSingboxInstallErr(err.Error())
		return err
	}
	if err := os.Rename(tmpPath, singboxConfigPath()); err != nil {
		return fmt.Errorf("替换 sing-box 配置失败: %v", err)
	}
	setSingboxInstallErr("")
	return nil
}

// checkSingboxConfig 让配置错误在启动前返回,避免后台崩溃重启循环。
func checkSingboxConfig() error {
	return checkSingboxConfigPath(singboxConfigPath())
}

func checkSingboxConfigPath(path string) error {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	out, err := exec.CommandContext(ctx, singboxExecPath(), "check", "-c", path).CombinedOutput()
	if err != nil {
		return fmt.Errorf("sing-box 配置校验失败: %v, %s", err, strings.TrimSpace(string(out)))
	}
	return nil
}

// reloadSingbox 重启由 Agent 直接托管的 sing-box 子进程。
func reloadSingbox() error {
	if err := persistSingboxEnabled(true); err != nil {
		return err
	}
	if err := stopSingbox(); err != nil {
		return err
	}
	return startSingbox()
}

func startSingbox() error {
	if !hasSingboxConfig() {
		return fmt.Errorf("sing-box 配置不存在")
	}
	if err := checkSingboxConfig(); err != nil {
		setSingboxInstallErr(err.Error())
		return err
	}

	bin := singboxExecPath()
	cmd := exec.Command(bin, "run", "-c", singboxConfigPath())
	cmd.Dir = installDir
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	done := make(chan struct{})

	// 启动和登记必须在同一把锁内完成,否则 stopSingbox 可能在进程登记前看到
	// singboxProcess=nil 并返回,随后新进程又绕过停止意图继续运行。
	singboxProcessMu.Lock()
	if singboxShutdown {
		singboxProcessMu.Unlock()
		return fmt.Errorf("Agent 正在退出,跳过启动 sing-box")
	}
	if singboxProcess != nil {
		singboxProcessMu.Unlock()
		return nil
	}
	singboxDesired = true
	if err := cmd.Start(); err != nil {
		singboxDesired = false
		singboxProcessMu.Unlock()
		msg := fmt.Sprintf("启动 sing-box 失败: %v", err)
		setSingboxInstallErr(msg)
		return fmt.Errorf("%s", msg)
	}
	singboxProcess = cmd
	singboxProcessDone = done
	singboxProcessMu.Unlock()

	go func() {
		err := cmd.Wait()
		exitMessage := "sing-box 进程已退出"
		if err != nil {
			exitMessage = fmt.Sprintf("sing-box 进程已退出: %v", err)
			fmt.Printf("⚠️ sing-box 进程已退出: %v\n", err)
		} else {
			fmt.Println("⚠️ sing-box 进程已退出")
		}
		singboxProcessMu.Lock()
		// 只要不是主动停止,无论进程以错误还是正常状态退出都尝试恢复。
		// desired=false 和 shutdown=true 是 stopSingbox 设置的两个闸门,可以阻断延迟重启。
		shouldRestart := singboxProcess == cmd && singboxDesired && !singboxShutdown
		if singboxProcess == cmd {
			singboxProcess = nil
			singboxProcessDone = nil
		}
		singboxProcessMu.Unlock()
		close(done)

		if shouldRestart {
			setSingboxInstallErr(exitMessage)

			// 配置错误、端口冲突等故障不能每 3 秒无限刷屏。连续失败时按
			// 3/6/12/24/48/60 秒退避,运行稳定 30 秒后由定时器清零。
			singboxRestartMu.Lock()
			singboxRestartFailures++
			failureCount := singboxRestartFailures
			singboxRestartMu.Unlock()
			delay := 3 * time.Second
			for i := 1; i < failureCount && delay < 60*time.Second; i++ {
				delay *= 2
			}
			if delay > 60*time.Second {
				delay = 60 * time.Second
			}
			time.Sleep(delay)
			singboxMu.Lock()
			defer singboxMu.Unlock()
			singboxProcessMu.Lock()
			canRestart := singboxDesired && !singboxShutdown && singboxProcess == nil
			singboxProcessMu.Unlock()
			if canRestart {
				if restartErr := startSingbox(); restartErr != nil {
					fmt.Printf("⚠️ sing-box 崩溃重启失败: %v\n", restartErr)
				}
			}
		}
	}()

	// 进程保持运行一段时间后,把之前的崩溃次数清零。这里检查 cmd 是否仍
	// 是当前进程,避免旧进程的定时器在新进程启动后误清零退避计数。
	time.AfterFunc(30*time.Second, func() {
		singboxProcessMu.Lock()
		stable := singboxProcess == cmd
		singboxProcessMu.Unlock()
		if stable {
			singboxRestartMu.Lock()
			singboxRestartFailures = 0
			singboxRestartMu.Unlock()
		}
	})

	select {
	case <-done:
		return fmt.Errorf("sing-box 启动后立即退出")
	case <-time.After(200 * time.Millisecond):
		setSingboxInstallErr("")
		return nil
	}
}

// StopSingbox 在 Agent 退出时调用,确保容器不会遗留子进程。
func StopSingbox() error {
	// 先置退出闸门,再等待配置锁。这样即使启动恢复正在做旧版裸机的
	// 下载/校验,它释放锁后也不会重新拉起 sing-box。
	singboxProcessMu.Lock()
	singboxShutdown = true
	singboxDesired = false
	singboxProcessMu.Unlock()

	singboxMu.Lock()
	defer singboxMu.Unlock()
	return stopSingbox()
}

func stopSingbox() error {
	singboxProcessMu.Lock()
	singboxDesired = false
	cmd := singboxProcess
	done := singboxProcessDone
	singboxProcessMu.Unlock()
	if cmd == nil || done == nil {
		return nil
	}

	if cmd.Process != nil {
		if err := cmd.Process.Signal(syscall.SIGTERM); err != nil {
			if !strings.Contains(strings.ToLower(err.Error()), "already finished") {
				fmt.Printf("⚠️ 停止 sing-box 发送 SIGTERM 失败: %v\n", err)
			}
		}
	}

	select {
	case <-done:
		return nil
	case <-time.After(10 * time.Second):
		if cmd.Process != nil {
			_ = cmd.Process.Kill()
		}
		select {
		case <-done:
			return nil
		case <-time.After(2 * time.Second):
			return fmt.Errorf("停止 sing-box 超时")
		}
	}
}

// prepareSingboxOnStart 兼容旧版裸机节点的运行时安装,并在容器重启后恢复
// 之前已经启用的协议配置。没有配置或存在 disabled 标记时保持停止。
func prepareSingboxOnStart() {
	singboxMu.Lock()
	defer singboxMu.Unlock()

	if err := ensureSingboxInstalled(""); err != nil {
		fmt.Printf("⚠️ 后台预装 sing-box 失败: %v\n", err)
		return
	}
	if !shouldRestoreSingbox() {
		fmt.Println("✅ sing-box 已就绪,当前没有需要恢复的协议配置")
		return
	}
	if err := ensureSelfCert(); err != nil {
		fmt.Printf("⚠️ 恢复 sing-box 证书失败: %v\n", err)
		return
	}
	if err := startSingbox(); err != nil {
		fmt.Printf("⚠️ 恢复 sing-box 失败: %v\n", err)
		return
	}
	fmt.Println("✅ 已从持久化配置恢复 sing-box")
}

// ---- 下载 / 解压工具 ----

func downloadFile(url, dest string) error {
	// 总超时给足(二进制十几兆,慢线路也得下完),但连不上/服务端不吭声要快速失败,
	// 否则国内机会在 GitHub 那一个源上干等十分钟,轮不到后面的镜像
	client := &http.Client{
		Timeout: 10 * time.Minute,
		Transport: &http.Transport{
			DialContext:           (&net.Dialer{Timeout: 15 * time.Second}).DialContext,
			TLSHandshakeTimeout:   15 * time.Second,
			ResponseHeaderTimeout: 30 * time.Second,
		},
	}
	resp, err := client.Get(url)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	tmpPath := dest + ".tmp"
	_ = os.Remove(tmpPath)
	out, err := os.Create(tmpPath)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, resp.Body); err != nil {
		_ = out.Close()
		_ = os.Remove(tmpPath)
		return err
	}
	if err := out.Close(); err != nil {
		_ = os.Remove(tmpPath)
		return err
	}
	if err := os.Rename(tmpPath, dest); err != nil {
		_ = os.Remove(tmpPath)
		return err
	}
	return nil
}

// extractSingboxBinary 从 sing-box release 的 tar.gz 里抽出 sing-box 二进制
// 归档结构形如 sing-box-1.13.12-linux-amd64/sing-box
func extractSingboxBinary(tarGzPath, dest string) error {
	f, err := os.Open(tarGzPath)
	if err != nil {
		return err
	}
	defer f.Close()

	gz, err := gzip.NewReader(f)
	if err != nil {
		return err
	}
	defer gz.Close()

	tr := tar.NewReader(gz)
	for {
		hdr, err := tr.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			return err
		}
		if hdr.Typeflag == tar.TypeReg && filepath.Base(hdr.Name) == "sing-box" {
			tmpPath := dest + ".tmp"
			_ = os.Remove(tmpPath)
			out, err := os.OpenFile(tmpPath, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
			if err != nil {
				return err
			}
			if _, err := io.Copy(out, tr); err != nil {
				_ = out.Close()
				_ = os.Remove(tmpPath)
				return err
			}
			if err := out.Close(); err != nil {
				_ = os.Remove(tmpPath)
				return err
			}
			if err := os.Chmod(tmpPath, 0o755); err != nil {
				_ = os.Remove(tmpPath)
				return err
			}
			if err := os.Rename(tmpPath, dest); err != nil {
				_ = os.Remove(tmpPath)
				return err
			}
			return nil
		}
	}
	return fmt.Errorf("归档里没找到 sing-box 二进制")
}
