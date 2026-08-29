package com.admin.common.utils;

import com.admin.common.dto.GostDto;
import com.admin.entity.Inbound;
import com.admin.entity.InboundUser;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.nio.charset.StandardCharsets;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * sing-box 配置生成 + 下发(合体面板 · 协议侧)。
 * 与 GostUtil 对称:GostUtil 管转发/限速的下发,SingboxUtil 管协议的下发。
 * 节点端 x/socket/singbox.go 收到 SetSingboxConfig 后写文件并由 Agent 管理 sing-box。
 * 约束:入站一律 listen 127.0.0.1,公网口交给 gost 转发并限速。
 */
public class SingboxUtil {

    /**
     * 生成某节点的完整 sing-box 配置(汇总该节点上所有入站),并通过 WebSocket 下发。
     *
     * @param nodeId          节点ID
     * @param inbounds        该节点上的入站列表
     * @param usersByInbound  入站ID -> 该入站下的用户凭证列表
     * @param mirror          国内 GitHub 镜像前缀(如 https://ghfast.top/),可为 null
     */
    public static GostDto SetSingboxConfig(Long nodeId, List<Inbound> inbounds,
                                           Map<Long, List<InboundUser>> usersByInbound,
                                           Map<Long, String> landingOutbounds, String mirror) {
        JSONObject payload = new JSONObject();
        payload.put("config", buildNodeConfig(inbounds, usersByInbound, landingOutbounds));
        if (mirror != null && !mirror.isEmpty()) {
            payload.put("mirror", mirror);
        }
        return WebSocketServer.send_msg(nodeId, payload, "SetSingboxConfig");
    }

    /** 关掉某节点的 sing-box */
    public static GostDto DeleteSingbox(Long nodeId) {
        return WebSocketServer.send_msg(nodeId, new JSONObject(), "DeleteSingbox");
    }

    /**
     * 中转:让【前置机节点】用给定的 socks 落地拨号测一下,回显出口 IP + 延迟。
     * outbound = LandingUtil 解出的 sing-box 出站(socks:server/server_port/username/password)。
     * 返回 GostDto.data = {ok, exitIp, latencyMs}(节点端 handleTestOutbound)。
     */
    public static GostDto TestOutbound(Long nodeId, JSONObject outbound) {
        JSONObject payload = new JSONObject();
        payload.put("type", outbound.getString("type"));
        payload.put("server", outbound.getString("server"));
        payload.put("port", outbound.getInteger("server_port"));
        if (outbound.containsKey("username")) {
            payload.put("username", outbound.getString("username"));
        }
        if (outbound.containsKey("password")) {
            payload.put("password", outbound.getString("password"));
        }
        return WebSocketServer.send_msg(nodeId, payload, "TestOutbound");
    }

    /**
     * 让节点用 sing-box 生成 Reality 密钥对。
     * 返回的 GostDto.data = {"privateKey": "...", "publicKey": "..."}(节点端 handleGenerateRealityKeypair)。
     */
    public static GostDto GenerateRealityKeypair(Long nodeId, String mirror) {
        JSONObject payload = new JSONObject();
        if (mirror != null && !mirror.isEmpty()) {
            payload.put("mirror", mirror);
        }
        return WebSocketServer.send_msg(nodeId, payload, "GenerateRealityKeypair");
    }

    /**
     * 生成 VLESS-Reality 客户端分享链接。
     * 地址填的是该用户的【gost 公网端口】(被限速/计流量/到期),不是 sing-box 本机口。
     */
    public static String buildVlessRealityLink(String uuid, String serverIp, Integer port,
                                               String sni, String publicKey, String shortId, String remark) {
        if (!hasText(uuid) || !validServerHost(serverIp) || !validPort(port)
                || !validServerName(sni) || !validRealityKey(publicKey) || !validRealityShortId(shortId)) {
            return "";
        }
        return "vless://" + urlEncode(uuid) + "@" + formatHostPort(serverIp, port)
                + "?encryption=none&flow=xtls-rprx-vision&security=reality"
                + "&sni=" + urlEncode(sni)
                + "&fp=chrome"
                + "&pbk=" + urlEncode(publicKey)
                + "&sid=" + urlEncode(shortId)
                + "&type=tcp#" + urlEncode(remark);
    }

    /**
     * 汇总一个节点的完整 sing-box 配置(log + 所有入站 + direct 出站 + 中转的落地出站/路由)。
     * landingOutbounds: 落地ID -> 该落地的 sing-box outbound JSON(不含 tag);入站带 landing_id 时按此路由出网。
     */
    public static JSONObject buildNodeConfig(List<Inbound> inbounds,
                                             Map<Long, List<InboundUser>> usersByInbound,
                                             Map<Long, String> landingOutbounds) {
        JSONObject log = new JSONObject();
        log.put("level", "warn");

        JSONArray inboundArr = new JSONArray();
        JSONArray outbounds = new JSONArray();
        JSONArray routeRules = new JSONArray();
        java.util.Set<Long> addedLandings = new java.util.HashSet<>();

        JSONObject direct = new JSONObject();
        direct.put("type", "direct");
        direct.put("tag", "direct");
        outbounds.add(direct);

        if (inbounds != null) {
            for (Inbound in : inbounds) {
                if (in.getStatus() != null && in.getStatus() == 0) {
                    continue; // 停用的入站不下发
                }
                List<InboundUser> users = usersByInbound != null ? usersByInbound.get(in.getId()) : null;
                JSONObject inboundJson = buildInbound(in, users);
                if (inboundJson == null) {
                    continue;
                }

                // 中转:该入站有落地 → 加落地出站(去重)+ 路由(该入站 tag → 落地出站)
                Long lid = in.getLandingId();
                String obJson = (lid != null && landingOutbounds != null) ? landingOutbounds.get(lid) : null;
                if (lid != null) {
                    // 中转配置缺失或损坏时整条入站都不下发。绝不能保留入站却没有
                    // 对应路由,否则 route.final=direct 会让本应走落地的流量意外直出。
                    if (!hasText(obJson)) {
                        continue;
                    }
                    String tag = "landing-" + lid;
                    if (addedLandings.add(lid)) {
                        JSONObject ob;
                        try {
                            ob = JSON.parseObject(obJson);
                        } catch (Exception ignored) {
                            addedLandings.remove(lid);
                            continue;
                        }
                        if (!validLandingOutbound(ob)) {
                            addedLandings.remove(lid);
                            continue;
                        }
                        ob.put("tag", tag);
                        outbounds.add(ob);
                    }
                    JSONObject rule = new JSONObject();
                    JSONArray inTags = new JSONArray();
                    inTags.add(in.getTag());
                    rule.put("inbound", inTags);
                    rule.put("outbound", tag);
                    routeRules.add(rule);
                }
                inboundArr.add(inboundJson);
            }
        }

        JSONObject config = new JSONObject();
        config.put("log", log);
        config.put("inbounds", inboundArr);
        config.put("outbounds", outbounds);
        // 有中转入站才写 route(纯直连节点保持原样,不影响协议管理)
        if (!routeRules.isEmpty()) {
            JSONObject route = new JSONObject();
            route.put("rules", routeRules);
            route.put("final", "direct");
            config.put("route", route);
        }
        return config;
    }

    /** 按协议生成单个 sing-box 入站 */
    public static JSONObject buildInbound(Inbound in, List<InboundUser> users) {
        if (in == null || !hasText(in.getTag()) || !validPort(in.getListenPort())) {
            return null;
        }
        String protocol = in.getProtocol() == null ? "" : in.getProtocol().trim().toLowerCase(java.util.Locale.ROOT);
        switch (protocol) {
            case "vless":
                return buildVlessReality(in, users);
            case "trojan":
                return buildTrojanReality(in, users);
            case "vmess":
                return buildVmess(in, users);
            case "shadowsocks":
                return buildShadowsocks(in);
            case "hysteria2":
                return buildHysteria2(in, users);
            case "tuic":
                return buildTuic(in, users);
            case "anytls":
                return buildAnyTls(in, users);
            default:
                return null;
        }
    }

    /**
     * Shadowsocks-2022 入站(无 TLS、不依赖客户端指纹,绕开 reality 的后量子坑)。
     * 单密码,用户靠各自的 gost 公网口区分/限速;method+password 存在 inbound.configJson。
     */
    private static JSONObject buildShadowsocks(Inbound in) {
        JSONObject cfg = parseConfig(in.getConfigJson());
        String method = normalizeShadowsocksMethod(cfg.getString("method"));
        String password = cfg.getString("password");
        if (!validShadowsocksPassword(method, password)) {
            return null;
        }
        JSONObject inbound = new JSONObject();
        inbound.put("type", "shadowsocks");
        inbound.put("tag", in.getTag());
        inbound.put("listen", "127.0.0.1");
        inbound.put("listen_port", in.getListenPort());
        inbound.put("method", method);
        inbound.put("password", password.trim());
        return inbound;
    }

    /** 生成 Shadowsocks 客户端分享链接(SIP002:ss://base64url(method:password)@ip:port#remark)。地址=gost 公网口 */
    public static String buildShadowsocksLink(String serverIp, Integer port, String method, String password, String remark) {
        String normalizedMethod = normalizeShadowsocksMethod(method);
        if (!validServerHost(serverIp) || !validPort(port) || !validShadowsocksPassword(normalizedMethod, password)) {
            return "";
        }
        String userinfo = Base64.getUrlEncoder().withoutPadding()
                .encodeToString((normalizedMethod + ":" + password.trim()).getBytes(StandardCharsets.UTF_8));
        return "ss://" + userinfo + "@" + formatHostPort(serverIp, port) + "#" + urlEncode(remark);
    }

    private static JSONObject parseConfig(String configJson) {
        if (configJson == null || configJson.isEmpty()) {
            return new JSONObject();
        }
        try {
            return JSON.parseObject(configJson);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static boolean validPort(Integer port) {
        return port != null && port >= 1 && port <= 65535;
    }

    private static boolean validLandingOutbound(JSONObject outbound) {
        if (outbound == null || !validServerHost(outbound.getString("server"))
                || !validPort(outbound.getInteger("server_port"))) {
            return false;
        }
        String type = outbound.getString("type");
        type = type == null ? "" : type.trim().toLowerCase(java.util.Locale.ROOT);
        switch (type) {
            case "socks":
                return true;
            case "shadowsocks":
                return hasText(outbound.getString("method")) && hasText(outbound.getString("password"));
            case "vmess":
            case "vless":
                return hasText(outbound.getString("uuid"));
            case "trojan":
            case "hysteria2":
                return hasText(outbound.getString("password"));
            case "tuic":
                return hasText(outbound.getString("uuid")) && hasText(outbound.getString("password"));
            case "anytls":
                return hasText(outbound.getString("password"));
            default:
                return false;
        }
    }

    private static boolean validRealityShortId(String shortId) {
        return hasText(shortId) && shortId.trim().matches("(?:[0-9a-fA-F]{2}){1,8}");
    }

    private static boolean validRealityKey(String key) {
        return hasText(key) && key.trim().matches("[A-Za-z0-9_-]{20,128}");
    }

    private static boolean validServerName(String value) {
        if (!hasText(value)) {
            return false;
        }
        String host = value.trim();
        return host.length() <= 253
                && host.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+");
    }

    private static boolean validShadowsocksMethod(String method) {
        String normalized = normalizeShadowsocksMethod(method);
        if (normalized == null) {
            return false;
        }
        return "2022-blake3-aes-128-gcm".equals(normalized)
                || "2022-blake3-aes-256-gcm".equals(normalized)
                || "2022-blake3-chacha20-poly1305".equals(normalized);
    }

    private static boolean validShadowsocksPassword(String method, String password) {
        if (!validShadowsocksMethod(method) || !hasText(password)) {
            return false;
        }
        try {
            int expected = normalizeShadowsocksMethod(method).contains("aes-128") ? 16 : 32;
            return decodeBase64(password).length == expected;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 同时兼容标准及 URL-safe Base64（分享链接可能省略 padding）。 */
    private static byte[] decodeBase64(String value) {
        String normalized = value == null ? "" : value.trim();
        try {
            return Base64.getDecoder().decode(normalized);
        } catch (IllegalArgumentException standardError) {
            try {
                return Base64.getUrlDecoder().decode(normalized);
            } catch (IllegalArgumentException urlError) {
                throw standardError;
            }
        }
    }

    private static String normalizeShadowsocksMethod(String method) {
        if (!hasText(method)) {
            return null;
        }
        return method.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String urlEncode(String s) {
        try {
            // URLEncoder 面向表单,会把空格编码成 '+'.分享链接的 fragment/query
            // 中 '+' 是字面字符,因此改成标准的 %20。
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8")
                    .replace("+", "%20");
        } catch (Exception e) {
            return "";
        }
    }

    /** URL 协议中的 host:port 格式。IPv6 必须使用方括号,否则冒号会和端口分隔符混淆。 */
    private static String formatHostPort(String host, Integer port) {
        String normalized = host == null ? "" : host.trim();
        boolean bracketed = normalized.startsWith("[");
        if (bracketed) {
            int closeBracket = normalized.indexOf(']');
            if (closeBracket <= 1 || closeBracket != normalized.lastIndexOf(']')) {
                return "";
            }
            String suffix = normalized.substring(closeBracket + 1);
            if (!suffix.isEmpty() && (!suffix.startsWith(":") || !validPortString(suffix.substring(1)))) {
                return "";
            }
            // 同时兼容传入 [IPv6] 和 [IPv6]:旧端口;端口统一使用当前线路端口。
            normalized = normalized.substring(1, closeBracket);
        }

        long colonCount = normalized.chars().filter(ch -> ch == ':').count();
        if (bracketed || colonCount > 1) {
            return "[" + normalized + "]:" + port;
        }
        if (colonCount == 1) {
            // 普通 host 不应携带端口;调用方会单独传入线路端口。
            return "";
        }
        return normalized + ":" + port;
    }

    /** 校验分享链接中的目标主机,避免把 URL/带端口字符串拼成双端口。 */
    private static boolean validServerHost(String host) {
        if (!hasText(host)) {
            return false;
        }
        String normalized = host.trim();
        boolean bracketed = normalized.startsWith("[") || normalized.endsWith("]");
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        } else if (normalized.startsWith("[") || normalized.endsWith("]")) {
            return false;
        }
        if (normalized.indexOf('/') >= 0 || normalized.indexOf('?') >= 0
                || normalized.indexOf('#') >= 0 || normalized.indexOf('@') >= 0) {
            return false;
        }
        if (normalized.indexOf(':') >= 0) {
            try {
                return InetAddress.getByName(normalized) instanceof Inet6Address;
            } catch (Exception e) {
                return false;
            }
        }
        if (bracketed || normalized.length() > 253) {
            return false;
        }
        if (normalized.matches("[0-9.]+")) {
            String[] octets = normalized.split("\\.", -1);
            if (octets.length != 4) {
                return false;
            }
            for (String octet : octets) {
                try {
                    if (octet.isEmpty() || Integer.parseInt(octet) > 255) {
                        return false;
                    }
                } catch (NumberFormatException e) {
                    return false;
                }
            }
            return true;
        }
        return normalized.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*");
    }

    private static boolean validPortString(String value) {
        try {
            return validPort(Integer.valueOf(value));
        } catch (Exception e) {
            return false;
        }
    }

    /** VLESS + Reality 入站(无域名);listen 一律 127.0.0.1,公网口交给 gost 限速 */
    private static JSONObject buildVlessReality(Inbound in, List<InboundUser> users) {
        if (!validServerName(in.getSni()) || !validRealityKey(in.getPrivateKey())
                || (hasText(in.getDest()) && !validServerName(in.getDest())) || !validRealityShortId(in.getShortId())) {
            return null;
        }
        JSONObject inbound = new JSONObject();
        inbound.put("type", "vless");
        inbound.put("tag", in.getTag());
        inbound.put("listen", "127.0.0.1");
        inbound.put("listen_port", in.getListenPort());

        JSONArray userArr = new JSONArray();
        if (users != null) {
            for (InboundUser u : users) {
                if (u.getUuid() == null || u.getUuid().isEmpty()) continue;
                if (u.getStatus() != null && u.getStatus() == 0) continue;
                JSONObject uj = new JSONObject();
                uj.put("uuid", u.getUuid());
                uj.put("flow", "xtls-rprx-vision");
                userArr.add(uj);
            }
        }
        inbound.put("users", userArr);
        inbound.put("tls", buildRealityTls(in));
        return inbound;
    }

    /** Trojan + Reality 入站(无域名);和 VLESS-Reality 同一套 reality,凭证是 password */
    private static JSONObject buildTrojanReality(Inbound in, List<InboundUser> users) {
        if (!validServerName(in.getSni()) || !validRealityKey(in.getPrivateKey())
                || (hasText(in.getDest()) && !validServerName(in.getDest())) || !validRealityShortId(in.getShortId())) {
            return null;
        }
        JSONObject inbound = new JSONObject();
        inbound.put("type", "trojan");
        inbound.put("tag", in.getTag());
        inbound.put("listen", "127.0.0.1");
        inbound.put("listen_port", in.getListenPort());

        JSONArray userArr = new JSONArray();
        if (users != null) {
            for (InboundUser u : users) {
                if (u.getPassword() == null || u.getPassword().isEmpty()) continue;
                if (u.getStatus() != null && u.getStatus() == 0) continue;
                JSONObject uj = new JSONObject();
                uj.put("password", u.getPassword());
                userArr.add(uj);
            }
        }
        inbound.put("users", userArr);
        inbound.put("tls", buildRealityTls(in));
        return inbound;
    }

    /** VMess 入站(TCP,无 TLS,无域名);凭证是 uuid */
    private static JSONObject buildVmess(Inbound in, List<InboundUser> users) {
        JSONObject inbound = new JSONObject();
        inbound.put("type", "vmess");
        inbound.put("tag", in.getTag());
        inbound.put("listen", "127.0.0.1");
        inbound.put("listen_port", in.getListenPort());

        JSONArray userArr = new JSONArray();
        if (users != null) {
            for (InboundUser u : users) {
                if (u.getUuid() == null || u.getUuid().isEmpty()) continue;
                if (u.getStatus() != null && u.getStatus() == 0) continue;
                JSONObject uj = new JSONObject();
                uj.put("uuid", u.getUuid());
                uj.put("alter_id", 0);
                userArr.add(uj);
            }
        }
        inbound.put("users", userArr);
        return inbound;
    }

    /** Reality over TLS 配置块(VLESS / Trojan 共用) */
    private static JSONObject buildRealityTls(Inbound in) {
        String sni = hasText(in.getSni()) ? in.getSni().trim() : "www.apple.com";
        String dest = hasText(in.getDest()) ? in.getDest().trim() : sni;
        JSONObject handshake = new JSONObject();
        handshake.put("server", dest);
        handshake.put("server_port", 443);

        JSONObject reality = new JSONObject();
        reality.put("enabled", true);
        reality.put("handshake", handshake);
        reality.put("private_key", in.getPrivateKey());
        // sing-box Reality 服务端 schema 要求 short_id 是十六进制字符串,
        // 不是客户端可接受的数组形式;数组会在启动前被 check 拒绝。
        reality.put("short_id", in.getShortId() == null ? "" : in.getShortId().trim());

        JSONObject tls = new JSONObject();
        tls.put("enabled", true);
        tls.put("server_name", sni);
        tls.put("reality", reality);
        return tls;
    }

    /** VMess 客户端链接(vmess://base64(json)) */
    public static String buildVmessLink(String uuid, String serverIp, Integer port, String remark) {
        if (!hasText(uuid) || !validServerHost(serverIp) || !validPort(port)) {
            return "";
        }
        JSONObject v = new JSONObject();
        v.put("v", "2");
        v.put("ps", remark == null ? "" : remark);
        // VMess JSON 的 add 字段只放主机本身;IPv6 的方括号只属于 URL
        // host:port 语法,写进 JSON 后会被部分客户端当成地址字符。
        String vmessHost = serverIp.trim();
        if (vmessHost.startsWith("[") && vmessHost.endsWith("]")) {
            vmessHost = vmessHost.substring(1, vmessHost.length() - 1);
        }
        v.put("add", vmessHost);
        v.put("port", String.valueOf(port));
        v.put("id", uuid);
        v.put("aid", "0");
        v.put("scy", "auto");
        v.put("net", "tcp");
        v.put("type", "none");
        v.put("host", "");
        v.put("path", "");
        v.put("tls", "");
        v.put("sni", "");
        String b64 = Base64.getEncoder().encodeToString(v.toJSONString().getBytes(StandardCharsets.UTF_8));
        return "vmess://" + b64;
    }

    /** Trojan + Reality 客户端链接 */
    public static String buildTrojanRealityLink(String password, String serverIp, Integer port,
                                                String sni, String publicKey, String shortId, String remark) {
        if (!hasText(password) || !validServerHost(serverIp) || !validPort(port)
                || !validServerName(sni) || !validRealityKey(publicKey) || !validRealityShortId(shortId)) {
            return "";
        }
        return "trojan://" + urlEncode(password.trim()) + "@" + formatHostPort(serverIp, port)
                + "?security=reality"
                + "&sni=" + urlEncode(sni)
                + "&fp=chrome"
                + "&pbk=" + urlEncode(publicKey)
                + "&sid=" + urlEncode(shortId)
                + "&type=tcp#" + urlEncode(remark);
    }

    // ---- 自签证书类协议(Hysteria2 / TUIC / AnyTLS,无域名,客户端 insecure)----
    // 证书由节点端自动生成,固定路径;面板配置直接引用。
    private static final String SELF_CERT = "/etc/gost/certs/self.crt";
    private static final String SELF_KEY = "/etc/gost/certs/self.key";

    /** 自签 TLS 配置块 */
    private static JSONObject buildSelfTls(Inbound in) {
        JSONObject tls = new JSONObject();
        tls.put("enabled", true);
        tls.put("server_name", selfTlsSni(in.getSni()));
        tls.put("certificate_path", SELF_CERT);
        tls.put("key_path", SELF_KEY);
        return tls;
    }

    /** Hysteria2 入站(QUIC/UDP,自签证书);凭证是 password */
    private static JSONObject buildHysteria2(Inbound in, List<InboundUser> users) {
        JSONObject inbound = new JSONObject();
        inbound.put("type", "hysteria2");
        inbound.put("tag", in.getTag());
        inbound.put("listen", "127.0.0.1");
        inbound.put("listen_port", in.getListenPort());
        JSONArray userArr = new JSONArray();
        if (users != null) {
            for (InboundUser u : users) {
                if (u.getPassword() == null || u.getPassword().isEmpty()) continue;
                if (u.getStatus() != null && u.getStatus() == 0) continue;
                JSONObject uj = new JSONObject();
                uj.put("password", u.getPassword());
                userArr.add(uj);
            }
        }
        inbound.put("users", userArr);
        inbound.put("tls", buildSelfTls(in));
        return inbound;
    }

    /** TUIC 入站(QUIC/UDP,自签证书,alpn h3);凭证是 uuid + password */
    private static JSONObject buildTuic(Inbound in, List<InboundUser> users) {
        JSONObject inbound = new JSONObject();
        inbound.put("type", "tuic");
        inbound.put("tag", in.getTag());
        inbound.put("listen", "127.0.0.1");
        inbound.put("listen_port", in.getListenPort());
        inbound.put("congestion_control", "bbr");
        JSONArray userArr = new JSONArray();
        if (users != null) {
            for (InboundUser u : users) {
                if (u.getUuid() == null || u.getUuid().isEmpty()) continue;
                if (!hasText(u.getPassword())) continue;
                if (u.getStatus() != null && u.getStatus() == 0) continue;
                JSONObject uj = new JSONObject();
                uj.put("uuid", u.getUuid());
                uj.put("password", u.getPassword());
                userArr.add(uj);
            }
        }
        inbound.put("users", userArr);
        JSONObject tls = buildSelfTls(in);
        JSONArray alpn = new JSONArray();
        alpn.add("h3");
        tls.put("alpn", alpn);
        inbound.put("tls", tls);
        return inbound;
    }

    /** AnyTLS 入站(TCP/TLS,自签证书);凭证是 password */
    private static JSONObject buildAnyTls(Inbound in, List<InboundUser> users) {
        JSONObject inbound = new JSONObject();
        inbound.put("type", "anytls");
        inbound.put("tag", in.getTag());
        inbound.put("listen", "127.0.0.1");
        inbound.put("listen_port", in.getListenPort());
        JSONArray userArr = new JSONArray();
        if (users != null) {
            for (InboundUser u : users) {
                if (u.getPassword() == null || u.getPassword().isEmpty()) continue;
                if (u.getStatus() != null && u.getStatus() == 0) continue;
                JSONObject uj = new JSONObject();
                uj.put("password", u.getPassword());
                userArr.add(uj);
            }
        }
        inbound.put("users", userArr);
        inbound.put("tls", buildSelfTls(in));
        return inbound;
    }

    /** Hysteria2 客户端链接 */
    public static String buildHysteria2Link(String password, String serverIp, Integer port, String sni, String remark) {
        if (!hasText(password) || !validServerHost(serverIp) || !validPort(port)) {
            return "";
        }
        return "hysteria2://" + urlEncode(password.trim()) + "@" + formatHostPort(serverIp, port)
                + "?sni=" + urlEncode(selfTlsSni(sni)) + "&insecure=1#" + urlEncode(remark);
    }

    /** TUIC 客户端链接 */
    public static String buildTuicLink(String uuid, String password, String serverIp, Integer port, String sni, String remark) {
        if (!hasText(uuid) || !hasText(password) || !validServerHost(serverIp) || !validPort(port)) {
            return "";
        }
        return "tuic://" + urlEncode(uuid.trim()) + ":" + urlEncode(password.trim()) + "@" + formatHostPort(serverIp, port)
                + "?congestion_control=bbr&udp_relay_mode=native&alpn=h3&sni=" + urlEncode(selfTlsSni(sni))
                + "&allow_insecure=1#" + urlEncode(remark);
    }

    /** AnyTLS 客户端链接 */
    public static String buildAnyTlsLink(String password, String serverIp, Integer port, String sni, String remark) {
        if (!hasText(password) || !validServerHost(serverIp) || !validPort(port)) {
            return "";
        }
        return "anytls://" + urlEncode(password.trim()) + "@" + formatHostPort(serverIp, port)
                + "?insecure=1&sni=" + urlEncode(selfTlsSni(sni)) + "#" + urlEncode(remark);
    }

    private static String selfTlsSni(String sni) {
        return hasText(sni) ? sni.trim() : "www.bing.com";
    }
}
