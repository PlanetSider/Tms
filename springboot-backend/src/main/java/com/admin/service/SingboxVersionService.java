package com.admin.service;

import com.admin.entity.Node;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class SingboxVersionService {

    /** 项目兼容版；必须与 go-gost/x/socket/singbox.go 中的 singboxVersion 保持一致。 */
    public static final String APPROVED_VERSION = "1.13.12";

    private static final String RELEASE_API = "https://api.github.com/repos/SagerNet/sing-box/releases/latest";
    private static final long CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000;
    private static final List<String> SUPPORTED_PROTOCOLS = Arrays.asList(
            "vless", "trojan", "vmess", "shadowsocks", "hysteria2", "tuic", "anytls");
    private static final Map<String, String> APPROVED_CHECKSUMS = new LinkedHashMap<>();

    /**
     * 只登记从官方 Release Notes 明确确认、且与本项目协议相关的版本变化。
     * 未登记的上游版本仍会显示“待适配”，但不会猜测受影响协议。
     */
    private static final Map<String, ReleaseImpact> RELEASE_IMPACTS = new LinkedHashMap<>();

    static {
        APPROVED_CHECKSUMS.put("amd64", "1540533adb3df24f5ad5f14b5c7ca3dbc2401b10a1c1eb278fcadcada47ec6c4");
        APPROVED_CHECKSUMS.put("arm64", "1ffa3b48ad6fa98f9fd810482e39bdd5b6157782ef11ce37d67bdcfd9338547a");
        RELEASE_IMPACTS.put("1.14.0", new ReleaseImpact(
                Arrays.asList("hysteria2", "tuic"),
                "Hysteria2 与共享 QUIC 组件存在更新，等待项目兼容验证"));
    }

    private volatile String upstreamVersion;
    private volatile boolean upstreamCheckFailed;
    private volatile long upstreamCheckedAt;

    @Scheduled(fixedDelay = CHECK_INTERVAL_MS, initialDelay = 5_000)
    public void refreshUpstreamVersion() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(RELEASE_API).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(5_000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "TMS-Panel");
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("GitHub API HTTP " + status);
            }

            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
            }
            JSONObject release = JSON.parseObject(body.toString());
            String latest = normalizeVersion(release == null ? null : release.getString("tag_name"));
            if (latest == null) {
                throw new IllegalStateException("上游响应缺少有效 tag_name");
            }
            upstreamVersion = latest;
            upstreamCheckFailed = false;
            upstreamCheckedAt = System.currentTimeMillis();
            log.info("sing-box 上游版本检查完成: upstream={}, compatible={}", latest, APPROVED_VERSION);
        } catch (Exception e) {
            upstreamCheckFailed = true;
            upstreamCheckedAt = System.currentTimeMillis();
            log.warn("sing-box 上游版本检查失败，继续使用缓存 {}: {}", upstreamVersion, e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public void decorateNode(Node node, String actualVersion) {
        decorateNode(node, actualVersion, upstreamVersion, upstreamCheckFailed, upstreamCheckedAt);
    }

    public JSONObject buildCompatibleUpgradePayload() {
        JSONObject payload = new JSONObject();
        payload.put("version", APPROVED_VERSION);
        payload.put("checksums", new LinkedHashMap<>(APPROVED_CHECKSUMS));
        return payload;
    }

    void decorateNode(Node node, String actualVersion, String latestVersion,
                      boolean checkFailed, long checkedAt) {
        String actual = normalizeVersion(actualVersion);
        String latest = normalizeVersion(latestVersion);
        node.setSingboxVersion(actual);
        node.setSingboxApprovedVersion(APPROVED_VERSION);
        node.setSingboxUpstreamVersion(latest);
        node.setSingboxVersionCheckFailed(checkFailed);
        node.setSingboxVersionCheckedAt(checkedAt > 0 ? checkedAt : null);

        if (actual == null) {
            applyState(node, "unknown", new ArrayList<>(), "节点尚未上报 sing-box 版本");
            return;
        }

        int approvedComparison = compareVersions(actual, APPROVED_VERSION);
        if (approvedComparison < 0) {
            List<String> affected = affectedProtocolsBetween(actual, APPROVED_VERSION);
            if (affected.isEmpty()) {
                affected = new ArrayList<>(SUPPORTED_PROTOCOLS);
            }
            applyState(node, "update_required", affected,
                    "节点版本低于项目兼容版 " + APPROVED_VERSION);
            return;
        }
        if (approvedComparison > 0) {
            applyState(node, "incompatible", new ArrayList<>(SUPPORTED_PROTOCOLS),
                    "节点版本高于项目兼容版，配置兼容性未经验证");
            return;
        }

        if (latest != null && compareVersions(latest, APPROVED_VERSION) > 0) {
            List<String> affected = affectedProtocolsBetween(APPROVED_VERSION, latest);
            String summary = releaseSummaryBetween(APPROVED_VERSION, latest);
            applyState(node, "upstream_pending", affected,
                    summary.isEmpty() ? "上游 " + latest + " 已发布，等待项目兼容验证" : summary);
            return;
        }

        applyState(node, "current", new ArrayList<>(), "当前为项目兼容版本");
    }

    private void applyState(Node node, String status, List<String> affected, String summary) {
        node.setSingboxVersionStatus(status);
        node.setSingboxAffectedProtocols(affected);
        node.setSingboxUpdateSummary(summary);
    }

    private List<String> affectedProtocolsBetween(String fromVersion, String toVersion) {
        Set<String> affected = new LinkedHashSet<>();
        for (Map.Entry<String, ReleaseImpact> entry : RELEASE_IMPACTS.entrySet()) {
            if (compareVersions(entry.getKey(), fromVersion) > 0
                    && compareVersions(entry.getKey(), toVersion) <= 0) {
                affected.addAll(entry.getValue().protocols);
            }
        }
        return new ArrayList<>(affected);
    }

    private String releaseSummaryBetween(String fromVersion, String toVersion) {
        List<String> summaries = new ArrayList<>();
        for (Map.Entry<String, ReleaseImpact> entry : RELEASE_IMPACTS.entrySet()) {
            if (compareVersions(entry.getKey(), fromVersion) > 0
                    && compareVersions(entry.getKey(), toVersion) <= 0) {
                summaries.add(entry.getKey() + ": " + entry.getValue().summary);
            }
        }
        return String.join("；", summaries);
    }

    static int compareVersions(String left, String right) {
        int[] leftParts = numericParts(left);
        int[] rightParts = numericParts(right);
        for (int i = 0; i < Math.max(leftParts.length, rightParts.length); i++) {
            int a = i < leftParts.length ? leftParts[i] : 0;
            int b = i < rightParts.length ? rightParts[i] : 0;
            if (a != b) {
                return Integer.compare(a, b);
            }
        }
        return 0;
    }

    private static int[] numericParts(String value) {
        String normalized = normalizeVersion(value);
        if (normalized == null) {
            return new int[]{0};
        }
        String core = normalized.split("[-+]", 2)[0];
        String[] parts = core.split("\\.");
        int[] numbers = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                numbers[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException ignored) {
                numbers[i] = 0;
            }
        }
        return numbers;
    }

    static String normalizeVersion(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        return normalized.matches("\\d+(?:\\.\\d+){1,3}(?:[-+][0-9A-Za-z.-]+)?") ? normalized : null;
    }

    private static class ReleaseImpact {
        private final List<String> protocols;
        private final String summary;

        private ReleaseImpact(List<String> protocols, String summary) {
            this.protocols = protocols;
            this.summary = summary;
        }
    }
}
