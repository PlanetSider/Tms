package com.admin.common.utils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import java.net.URLDecoder;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 把一条节点分享链接解析成 sing-box 出站(outbound)。
 * 支持:socks5:// / ss:// / vmess:// / vless:// / trojan:// / hysteria2://(hy2://)
 * / tuic:// / anytls://。
 *
 * 解析结果不含 "tag";下发节点配置时由 SingboxUtil 填 "landing-<id>",这样一条落地能被多台前置机复用。
 * 落地多为任意第三方节点(住宅代理 / 机场),TLS 一律 insecure=true(不校验证书),reality 除外(用 pbk 校验)。
 */
public class LandingUtil {

    /** 解析结果:type=协议类型(存 landing.type),outbound=sing-box 出站(不含 tag) */
    public static class Parsed {
        public String type;
        public JSONObject outbound;
        public Parsed(String type, JSONObject outbound) {
            this.type = type;
            this.outbound = outbound;
        }
    }

    /** 解析一条分享链接;失败抛 IllegalArgumentException(带中文原因) */
    public static Parsed parse(String link) {
        if (link == null) {
            throw new IllegalArgumentException("链接为空");
        }
        String s = link.trim();
        String lower = s.toLowerCase(Locale.ROOT);
        try {
            if (lower.startsWith("socks5://") || lower.startsWith("socks://") || lower.startsWith("socks4://")) {
                return new Parsed("socks5", parseSocks(s));
            }
            if (lower.startsWith("ss://")) {
                return new Parsed("shadowsocks", parseShadowsocks(s));
            }
            if (lower.startsWith("vmess://")) {
                return new Parsed("vmess", parseVmess(s));
            }
            if (lower.startsWith("vless://")) {
                return new Parsed("vless", parseVless(s));
            }
            if (lower.startsWith("trojan://")) {
                return new Parsed("trojan", parseTrojan(s));
            }
            if (lower.startsWith("hysteria2://") || lower.startsWith("hy2://")) {
                return new Parsed("hysteria2", parseHysteria2(s));
            }
            if (lower.startsWith("tuic://")) {
                return new Parsed("tuic", parseTuic(s));
            }
            if (lower.startsWith("anytls://")) {
                return new Parsed("anytls", parseAnyTls(s));
            }
            // 没协议头 → 当住宅 socks 的裸格式:IP:端口 / IP:端口:账号:密码 / 账号:密码@IP:端口
            return new Parsed("socks5", parseBareSocks(s));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("链接解析失败:" + e.getMessage());
        }
    }

    // ---------- socks5://[user:pass@]host:port ----------
    private static JSONObject parseSocks(String s) {
        String body = stripScheme(s);
        body = stripFragment(body);
        // 去掉可能的 query(?后)
        int q = body.indexOf('?');
        if (q >= 0) body = body.substring(0, q);

        String user = null, pass = null, hostPort;
        int at = body.lastIndexOf('@');
        if (at >= 0) {
            String userinfo = body.substring(0, at);
            hostPort = body.substring(at + 1);
            // userinfo 可能是明文 user:pass,也可能整体 base64
            if (userinfo.contains(":")) {
                String[] up = userinfo.split(":", 2);
                user = urlDecode(up[0]);
                pass = urlDecode(up[1]);
            } else {
                String dec = tryBase64(userinfo);
                if (dec != null && dec.contains(":")) {
                    String[] up = dec.split(":", 2);
                    user = up[0];
                    pass = up[1];
                } else {
                    user = urlDecode(userinfo);
                }
            }
        } else {
            hostPort = body;
        }
        String[] hp;
        if (hostPort.startsWith("[") && hostPort.indexOf(']') >= 0) {
            int rb = hostPort.indexOf(']');
            if (rb <= 1 || rb + 1 >= hostPort.length() || hostPort.charAt(rb + 1) != ':') {
                throw new IllegalArgumentException("IPv6 主机地址格式无效");
            }
            // ] 后第一个冒号是主机和端口的分隔符;只有端口数字之后的
            // 下一个冒号才可能是兼容格式中的 user:pass 分隔符。
            int portEnd = hostPort.indexOf(':', rb + 2);
            String address = portEnd < 0 ? hostPort : hostPort.substring(0, portEnd);
            hp = splitHostPort(address);
            if (portEnd >= 0 && (user == null || user.isEmpty())) {
                String[] credentials = hostPort.substring(portEnd + 1).split(":", 2);
                user = urlDecode(credentials[0]);
                pass = credentials.length > 1 ? urlDecode(credentials[1]) : null;
            }
        } else {
            hp = splitHostPort(hostPort);
        }
        return buildSocks(hp[0], hp[1], user, pass);
    }

    /**
     * 裸 socks 格式(住宅代理最常见给法,无协议头):
     * IP:端口 / IP:端口:账号:密码 / 账号:密码@IP:端口
     */
    private static JSONObject parseBareSocks(String s) {
        s = stripFragment(s).trim();
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);

        String user = null, pass = null, host, port;
        if (s.contains("@")) {
            int at = s.lastIndexOf('@');
            String ui = s.substring(0, at);
            if (ui.contains(":")) {
                String[] u = ui.split(":", 2);
                user = urlDecode(u[0]);
                pass = urlDecode(u[1]);
            } else {
                user = urlDecode(ui);
            }
            String[] hp = splitHostPort(s.substring(at + 1));
            host = hp[0];
            port = hp[1];
        } else if (s.startsWith("[")) {
            // [IPv6]:端口[:账号:密码],凭证字段里的冒号只从端口后的部分拆分。
            int rb = s.indexOf(']');
            if (rb <= 1 || rb + 1 >= s.length() || s.charAt(rb + 1) != ':') {
                throw new IllegalArgumentException("IPv6 主机地址格式无效");
            }
            int portEnd = s.indexOf(':', rb + 2);
            String hostPort = portEnd < 0 ? s : s.substring(0, portEnd);
            String[] hp = splitHostPort(hostPort);
            host = hp[0];
            port = hp[1];
            if (portEnd >= 0) {
                String[] credentials = s.substring(portEnd + 1).split(":", 2);
                user = urlDecode(credentials[0]);
                pass = credentials.length > 1 ? urlDecode(credentials[1]) : null;
            }
        } else if (s.chars().filter(ch -> ch == ':').count() > 1) {
            // 裸 IPv6:从右向左寻找「最长的合法 IPv6 主机 + 端口」边界,
            // 再把端口后的部分解释成账号和密码。这样既支持无方括号旧数据,
            // 也不会把 IPv6 的前几段误当成主机/端口。
            String[] hp = splitBareIpv6HostPort(s);
            host = hp[0];
            port = hp[1];
            user = hp[2] == null ? null : urlDecode(hp[2]);
            pass = hp[3] == null ? null : urlDecode(hp[3]);
        } else {
            String[] p = s.split(":", 4); // IP:端口[:账号:密码](密码里的冒号保留在最后一段)
            if (p.length < 2) {
                throw new IllegalArgumentException(
                        "落地链接不认识。住宅 socks 用 IP:端口 或 IP:端口:账号:密码;协议节点用带头的 socks5:// ss:// vmess:// vless:// trojan:// hysteria2:// tuic:// anytls://");
            }
            host = p[0];
            port = p[1];
            if (p.length >= 4) {
                user = urlDecode(p[2]);
                pass = urlDecode(p[3]);
            } else if (p.length == 3) {
                user = urlDecode(p[2]);
            }
        }
        try {
            Integer.parseInt(port.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("落地链接的端口不对。住宅 socks 用 IP:端口:账号:密码");
        }
        return buildSocks(host, port, user, pass);
    }

    private static JSONObject buildSocks(String host, String port, String user, String pass) {
        validateHostPort(host, port);
        JSONObject o = new JSONObject();
        o.put("type", "socks");
        o.put("server", host);
        o.put("server_port", Integer.parseInt(port.trim()));
        o.put("version", "5");
        if (user != null && !user.isEmpty()) {
            o.put("username", user);
            o.put("password", pass == null ? "" : pass);
        }
        return o;
    }

    // ---------- ss://  SIP002 或 legacy ----------
    private static JSONObject parseShadowsocks(String s) {
        String body = stripScheme(s);
        body = stripFragment(body);
        int q = body.indexOf('?');
        if (q >= 0) body = body.substring(0, q);

        String method, password, host, port;
        int at = body.lastIndexOf('@');
        if (at >= 0) {
            // SIP002: base64url(method:password)@host:port
            String userinfo = body.substring(0, at);
            String hostPort = body.substring(at + 1);
            // 先尝试原文 Base64,再尝试 URL 解码后的 Base64。明文
            // method:password 含冒号,不会被误当成一段 Base64 垃圾。
            String dec = tryBase64(userinfo);
            if (dec == null || !dec.contains(":")) {
                String unescaped = urlDecode(userinfo);
                dec = tryBase64(unescaped);
                if (dec == null || !dec.contains(":")) dec = unescaped;
            }
            String[] mp = dec.split(":", 2);
            if (mp.length < 2) throw new IllegalArgumentException("ss 链接缺少 method 或密码");
            method = mp[0];
            password = mp.length > 1 ? mp[1] : "";
            String[] hp = splitHostPort(hostPort);
            host = hp[0];
            port = hp[1];
        } else {
            // legacy: base64(method:password@host:port)
            String dec = tryBase64(body);
            if (dec == null) throw new IllegalArgumentException("ss 链接无法解码");
            int a2 = dec.lastIndexOf('@');
            if (a2 <= 0 || a2 >= dec.length() - 1) {
                throw new IllegalArgumentException("ss 链接缺少 method、密码或主机");
            }
            String mp = dec.substring(0, a2);
            String[] m = mp.split(":", 2);
            if (m.length < 2) throw new IllegalArgumentException("ss 链接缺少 method 或密码");
            method = m[0];
            password = m.length > 1 ? m[1] : "";
            String[] hp = splitHostPort(dec.substring(a2 + 1));
            host = hp[0];
            port = hp[1];
        }
        JSONObject o = new JSONObject();
        method = method == null ? "" : method.trim().toLowerCase(java.util.Locale.ROOT);
        password = password == null ? "" : password.trim();
        if (!validShadowsocksPassword(method, password)) {
            throw new IllegalArgumentException("ss-2022 方法或密钥无效");
        }
        o.put("type", "shadowsocks");
        o.put("server", host);
        o.put("server_port", Integer.parseInt(port));
        o.put("method", method);
        o.put("password", password);
        return o;
    }

    private static boolean validShadowsocksPassword(String method, String password) {
        if (method == null || password == null) {
            return false;
        }
        if (method.isEmpty() || password.isEmpty() || !method.matches("[a-z0-9][a-z0-9-]*")) {
            return false;
        }
        int expected;
        switch (method) {
            case "2022-blake3-aes-128-gcm":
                expected = 16;
                break;
            case "2022-blake3-aes-256-gcm":
            case "2022-blake3-chacha20-poly1305":
                expected = 32;
                break;
            default:
                // 落地允许历史 SS 方法(aes-256-gcm、chacha20-ietf-poly1305 等),
                // 具体算法由 sing-box 校验;这里只拦截空值和明显畸形值。
                return true;
        }
        try {
            return decodeBase64(password).length == expected;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 同时兼容标准及 URL-safe Base64（分享链接经常省略 padding）。 */
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

    // ---------- vmess://base64(json) ----------
    private static JSONObject parseVmess(String s) {
        String b = stripScheme(s);
        b = stripFragment(b);
        String dec = tryBase64(b);
        if (dec == null) throw new IllegalArgumentException("vmess 链接无法解码");
        JSONObject v = JSON.parseObject(dec);
        if (v == null || isBlank(v.getString("id"))) {
            throw new IllegalArgumentException("vmess 链接缺少 UUID");
        }
        String vmessServer = v.getString("add");
        if (vmessServer != null && vmessServer.startsWith("[") && vmessServer.endsWith("]")) {
            vmessServer = vmessServer.substring(1, vmessServer.length() - 1);
        }
        validateHostPort(vmessServer, v.getString("port"));
        JSONObject o = new JSONObject();
        o.put("type", "vmess");
        o.put("server", vmessServer);
        o.put("server_port", toInt(v.getString("port"), 0));
        o.put("uuid", v.getString("id"));
        o.put("security", isBlank(v.getString("scy")) ? "auto" : v.getString("scy"));
        o.put("alter_id", toInt(v.getString("aid"), 0));
        String tls = v.getString("tls");
        String host = v.getString("host");
        String sni = v.getString("sni");
        if ("tls".equalsIgnoreCase(tls)) {
            o.put("tls", tlsBlock(isBlank(sni) ? (isBlank(host) ? v.getString("add") : host) : sni, null, null, null));
        }
        JSONObject tr = transport(v.getString("net"), v.getString("path"), host);
        if (tr != null) o.put("transport", tr);
        return o;
    }

    // ---------- vless://uuid@host:port?params#tag ----------
    private static JSONObject parseVless(String s) {
        String body = stripScheme(s);
        body = stripFragment(body);
        Map<String, String> q = new HashMap<>();
        int qi = body.indexOf('?');
        if (qi >= 0) {
            q = parseQuery(body.substring(qi + 1));
            body = body.substring(0, qi);
        }
        int at = body.lastIndexOf('@');
        if (at <= 0) throw new IllegalArgumentException("vless 链接缺少 UUID 或主机");
        String uuid = urlDecode(body.substring(0, at));
        if (isBlank(uuid)) throw new IllegalArgumentException("vless 链接缺少 UUID");
        String[] hp = splitHostPort(body.substring(at + 1));

        JSONObject o = new JSONObject();
        o.put("type", "vless");
        o.put("server", hp[0]);
        o.put("server_port", Integer.parseInt(hp[1]));
        o.put("uuid", uuid);
        String flow = q.get("flow");
        if (!isBlank(flow)) o.put("flow", flow);

        String security = q.getOrDefault("security", "none");
        String sni = firstNonBlank(q.get("sni"), q.get("peer"), hp[0]);
        String fp = firstNonBlank(q.get("fp"), "chrome");
        if ("reality".equalsIgnoreCase(security)) {
            if (isBlank(q.get("pbk"))) {
                throw new IllegalArgumentException("VLESS-Reality 链接缺少公钥 pbk");
            }
            o.put("tls", tlsBlock(sni, fp, q.get("pbk"), q.get("sid")));
        } else if ("tls".equalsIgnoreCase(security) || "xtls".equalsIgnoreCase(security)) {
            o.put("tls", tlsBlock(sni, fp, null, null));
        }
        JSONObject tr = transport(q.get("type"), firstNonBlank(q.get("path"), q.get("serviceName")), q.get("host"));
        if (tr != null) o.put("transport", tr);
        return o;
    }

    // ---------- trojan://password@host:port?params#tag ----------
    private static JSONObject parseTrojan(String s) {
        String body = stripScheme(s);
        body = stripFragment(body);
        Map<String, String> q = new HashMap<>();
        int qi = body.indexOf('?');
        if (qi >= 0) {
            q = parseQuery(body.substring(qi + 1));
            body = body.substring(0, qi);
        }
        int at = body.lastIndexOf('@');
        if (at <= 0) throw new IllegalArgumentException("trojan 链接缺少密码或主机");
        String password = urlDecode(body.substring(0, at));
        if (isBlank(password)) throw new IllegalArgumentException("trojan 链接缺少密码");
        String[] hp = splitHostPort(body.substring(at + 1));

        JSONObject o = new JSONObject();
        o.put("type", "trojan");
        o.put("server", hp[0]);
        o.put("server_port", Integer.parseInt(hp[1]));
        o.put("password", password);
        String sni = firstNonBlank(q.get("sni"), q.get("peer"), hp[0]);
        // Trojan-Reality 必须保留 pbk/sid,否则落地 outbound 会被误当成普通 TLS。
        if ("reality".equalsIgnoreCase(q.get("security")) && isBlank(q.get("pbk"))) {
            throw new IllegalArgumentException("Trojan-Reality 链接缺少公钥 pbk");
        }
        o.put("tls", tlsBlock(sni, firstNonBlank(q.get("fp"), "chrome"), q.get("pbk"), q.get("sid")));
        JSONObject tr = transport(q.get("type"), firstNonBlank(q.get("path"), q.get("serviceName")), q.get("host"));
        if (tr != null) o.put("transport", tr);
        return o;
    }

    // ---------- hysteria2://password@host:port?params#tag ----------
    private static JSONObject parseHysteria2(String s) {
        String body = stripScheme(s);
        body = stripFragment(body);
        Map<String, String> q = new HashMap<>();
        int qi = body.indexOf('?');
        if (qi >= 0) {
            q = parseQuery(body.substring(qi + 1));
            body = body.substring(0, qi);
        }
        int at = body.lastIndexOf('@');
        if (at <= 0) throw new IllegalArgumentException("hysteria2 链接缺少密码或主机");
        String password = urlDecode(body.substring(0, at));
        if (isBlank(password)) throw new IllegalArgumentException("hysteria2 链接缺少密码");
        String[] hp = splitHostPort(body.substring(at + 1));

        JSONObject o = new JSONObject();
        o.put("type", "hysteria2");
        o.put("server", hp[0]);
        o.put("server_port", Integer.parseInt(hp[1]));
        o.put("password", password);
        String sni = firstNonBlank(q.get("sni"), q.get("peer"), hp[0]);
        o.put("tls", tlsBlock(sni, null, null, null));
        String obfs = q.get("obfs");
        if (!isBlank(obfs)) {
            JSONObject ob = new JSONObject();
            ob.put("type", obfs);
            ob.put("password", firstNonBlank(q.get("obfs-password"), q.get("obfsParam"), ""));
            o.put("obfs", ob);
        }
        return o;
    }

    // ---------- tuic://uuid:password@host:port ----------
    private static JSONObject parseTuic(String s) {
        String body = stripScheme(s);
        body = stripFragment(body);
        Map<String, String> q = new HashMap<>();
        int qi = body.indexOf('?');
        if (qi >= 0) {
            q = parseQuery(body.substring(qi + 1));
            body = body.substring(0, qi);
        }
        int at = body.lastIndexOf('@');
        if (at <= 0) throw new IllegalArgumentException("tuic 链接缺少 UUID 或主机");
        String[] credentials = body.substring(0, at).split(":", 2);
        if (credentials.length != 2) throw new IllegalArgumentException("tuic 链接缺少密码");
        String uuid = urlDecode(credentials[0]);
        String password = urlDecode(credentials[1]);
        if (isBlank(uuid) || isBlank(password)) throw new IllegalArgumentException("tuic 链接缺少 UUID 或密码");
        String[] hp = splitHostPort(body.substring(at + 1));

        JSONObject o = new JSONObject();
        o.put("type", "tuic");
        o.put("server", hp[0]);
        o.put("server_port", Integer.parseInt(hp[1]));
        o.put("uuid", uuid);
        o.put("password", password);
        if (!isBlank(q.get("congestion_control"))) o.put("congestion_control", q.get("congestion_control"));
        if (!isBlank(q.get("udp_relay_mode"))) o.put("udp_relay_mode", q.get("udp_relay_mode"));
        JSONObject tls = tlsBlock(firstNonBlank(q.get("sni"), q.get("peer"), hp[0]), null, null, null);
        String alpn = q.get("alpn");
        if (!isBlank(alpn)) {
            com.alibaba.fastjson.JSONArray alpns = new com.alibaba.fastjson.JSONArray();
            for (String item : alpn.split(",")) if (!isBlank(item)) alpns.add(item.trim());
            if (!alpns.isEmpty()) tls.put("alpn", alpns);
        }
        o.put("tls", tls);
        return o;
    }

    // ---------- anytls://password@host:port ----------
    private static JSONObject parseAnyTls(String s) {
        String body = stripScheme(s);
        body = stripFragment(body);
        Map<String, String> q = new HashMap<>();
        int qi = body.indexOf('?');
        if (qi >= 0) {
            q = parseQuery(body.substring(qi + 1));
            body = body.substring(0, qi);
        }
        int at = body.lastIndexOf('@');
        if (at <= 0) throw new IllegalArgumentException("anytls 链接缺少密码或主机");
        String password = urlDecode(body.substring(0, at));
        if (isBlank(password)) throw new IllegalArgumentException("anytls 链接缺少密码");
        String[] hp = splitHostPort(body.substring(at + 1));

        JSONObject o = new JSONObject();
        o.put("type", "anytls");
        o.put("server", hp[0]);
        o.put("server_port", Integer.parseInt(hp[1]));
        o.put("password", password);
        o.put("tls", tlsBlock(firstNonBlank(q.get("sni"), q.get("peer"), hp[0]), null, null, null));
        return o;
    }

    // ---------- helpers ----------

    /** 生成 sing-box tls 块;pbk 非空 → reality(用公钥校验),否则普通 tls + insecure(落地是任意节点,不校验证书) */
    private static JSONObject tlsBlock(String sni, String fp, String pbk, String sid) {
        JSONObject tls = new JSONObject();
        tls.put("enabled", true);
        if (!isBlank(sni)) tls.put("server_name", sni);
        if (!isBlank(pbk)) {
            if (!isValidRealityPublicKey(pbk) || (!isBlank(sid) && !isValidRealityShortId(sid))) {
                throw new IllegalArgumentException("Reality 公钥或 short-id 格式无效");
            }
            // reality:用 utls 指纹 + 公钥校验,不 insecure
            JSONObject utls = new JSONObject();
            utls.put("enabled", true);
            utls.put("fingerprint", isBlank(fp) ? "chrome" : fp);
            tls.put("utls", utls);
            JSONObject reality = new JSONObject();
            reality.put("enabled", true);
            reality.put("public_key", pbk);
            if (!isBlank(sid)) reality.put("short_id", sid);
            tls.put("reality", reality);
        } else {
            tls.put("insecure", true);
            if (!isBlank(fp)) {
                JSONObject utls = new JSONObject();
                utls.put("enabled", true);
                utls.put("fingerprint", fp);
                tls.put("utls", utls);
            }
        }
        return tls;
    }

    private static boolean isValidRealityPublicKey(String key) {
        return key != null && key.trim().matches("[A-Za-z0-9_-]{20,128}");
    }

    private static boolean isValidRealityShortId(String value) {
        return value != null && value.trim().matches("(?:[0-9a-fA-F]{2}){1,8}");
    }

    /** ws/grpc 传输;tcp/空 返回 null(默认 tcp,不写 transport) */
    private static JSONObject transport(String net, String path, String host) {
        if (isBlank(net)) return null;
        net = net.toLowerCase();
        if ("ws".equals(net)) {
            JSONObject t = new JSONObject();
            t.put("type", "ws");
            if (!isBlank(path)) t.put("path", path);
            if (!isBlank(host)) {
                JSONObject headers = new JSONObject();
                headers.put("Host", host);
                t.put("headers", headers);
            }
            return t;
        }
        if ("grpc".equals(net)) {
            JSONObject t = new JSONObject();
            t.put("type", "grpc");
            if (!isBlank(path)) t.put("service_name", path);
            return t;
        }
        if ("http".equals(net) || "h2".equals(net)) {
            JSONObject t = new JSONObject();
            t.put("type", "http");
            if (!isBlank(path)) t.put("path", path);
            if (!isBlank(host)) {
                com.alibaba.fastjson.JSONArray hosts = new com.alibaba.fastjson.JSONArray();
                hosts.add(host);
                t.put("host", hosts);
            }
            return t;
        }
        return null; // tcp / 其它:默认,不写
    }

    private static String stripScheme(String s) {
        int i = s.indexOf("://");
        return i >= 0 ? s.substring(i + 3) : s;
    }

    private static String stripFragment(String s) {
        int i = s.indexOf('#');
        return i >= 0 ? s.substring(0, i) : s;
    }

    private static String[] splitHostPort(String hp) {
        if (hp == null || hp.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少主机地址和端口");
        }
        hp = hp.trim();
        // URI 分享链接有时把空路径写成 host:port/ (例如
        // hysteria2://...@host:443/?sni=...). 这不是实际路径，兼容地去掉
        // 末尾单个斜杠；真实路径或多余斜杠仍交给主机校验拒绝。
        if (hp.endsWith("/")) {
            hp = hp.substring(0, hp.length() - 1);
        }
        // 支持 [ipv6]:port
        if (hp.startsWith("[")) {
            int rb = hp.indexOf(']');
            if (rb <= 1 || rb != hp.lastIndexOf(']') || rb + 1 >= hp.length() || hp.charAt(rb + 1) != ':') {
                throw new IllegalArgumentException("IPv6 主机地址格式无效");
            }
            String host = hp.substring(1, rb);
            String port = hp.substring(rb + 2);
            validateHostPort(host, port);
            return new String[]{host, port};
        }
        int c = hp.lastIndexOf(':');
        if (c < 0) throw new IllegalArgumentException("缺少端口:" + hp);
        String host = hp.substring(0, c);
        String port = hp.substring(c + 1);
        validateHostPort(host, port);
        return new String[]{host, port};
    }

    private static String[] splitBareIpv6HostPort(String value) {
        for (int i = value.length() - 1; i > 0; i--) {
            if (value.charAt(i) != ':') continue;
            String host = value.substring(0, i);
            if (!isIpv6(host)) continue;
            String rest = value.substring(i + 1);
            int next = rest.indexOf(':');
            String port = next < 0 ? rest : rest.substring(0, next);
            try {
                int parsed = Integer.parseInt(port);
                if (parsed < 1 || parsed > 65535) continue;
            } catch (NumberFormatException e) {
                continue;
            }
            String user = null;
            String pass = null;
            if (next >= 0) {
                String[] credentials = rest.substring(next + 1).split(":", 2);
                user = credentials[0];
                pass = credentials.length > 1 ? credentials[1] : null;
            }
            return new String[]{host, port, user, pass};
        }
        throw new IllegalArgumentException("IPv6 主机地址格式无效,请使用 [IPv6]:端口");
    }

    private static boolean isIpv6(String host) {
        try {
            return InetAddress.getByName(host) instanceof Inet6Address;
        } catch (Exception e) {
            return false;
        }
    }

    private static void validateHostPort(String host, String port) {
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少主机地址");
        }
        String h = host.trim();
        if (h.indexOf('/') >= 0 || h.indexOf('?') >= 0 || h.indexOf('#') >= 0 || h.indexOf('@') >= 0) {
            throw new IllegalArgumentException("主机地址格式无效");
        }
        if (h.indexOf(':') >= 0) {
            try {
                if (!(InetAddress.getByName(h) instanceof Inet6Address)) {
                    throw new IllegalArgumentException("IPv6 主机地址格式无效");
                }
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException("IPv6 主机地址格式无效");
            }
        } else if (!h.matches("[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?")) {
            throw new IllegalArgumentException("主机地址格式无效");
        }
        try {
            int value = Integer.parseInt(port == null ? "" : port.trim());
            if (value < 1 || value > 65535) {
                throw new IllegalArgumentException("端口必须在1-65535范围内");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("端口必须在1-65535范围内");
        }
    }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> m = new HashMap<>();
        for (String kv : q.split("&")) {
            if (kv.isEmpty()) continue;
            int e = kv.indexOf('=');
            if (e < 0) {
                m.put(urlDecode(kv), "");
            } else {
                m.put(urlDecode(kv.substring(0, e)), urlDecode(kv.substring(e + 1)));
            }
        }
        return m;
    }

    /** 尝试 base64(标准/url,带或不带 padding)解码;不像 base64 返回 null */
    private static String tryBase64(String s) {
        if (s == null || s.isEmpty()) return null;
        String t = s.replace('-', '+').replace('_', '/').trim();
        int pad = t.length() % 4;
        if (pad != 0) {
            StringBuilder sb = new StringBuilder(t);
            for (int i = 0; i < 4 - pad; i++) sb.append('=');
            t = sb.toString();
        }
        try {
            byte[] b = Base64.getDecoder().decode(t);
            String out = new String(b, StandardCharsets.UTF_8);
            // 粗判:解出来应可打印(避免把普通字符串误当 base64)
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static String urlDecode(String s) {
        try {
            // URLDecoder 是表单语义,会把 URI 用户信息/查询参数里的字面 '+'
            // 转成空格。分享链接遵循 URI 百分号编码,未编码的 '+' 应原样保留。
            return URLDecoder.decode(s == null ? "" : s.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static int toInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String firstNonBlank(String... arr) {
        for (String a : arr) {
            if (!isBlank(a)) return a;
        }
        return null;
    }
}
