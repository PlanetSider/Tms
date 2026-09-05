package com.admin.common.utils;


import com.admin.common.dto.GostConfigDto;
import com.admin.common.dto.GostDto;
import com.admin.common.task.CheckGostConfigAsync;
import com.admin.entity.Node;
import com.admin.service.InboundService;
import com.admin.service.NodeService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import javax.annotation.Resource;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;


@Slf4j
public class WebSocketServer extends TextWebSocketHandler {

    @Resource
    NodeService nodeService;

    @Resource
    InboundService inboundService;

    // 存储所有活跃的 WebSocket 连接（
    private static final CopyOnWriteArraySet<WebSocketSession> activeSessions = new CopyOnWriteArraySet<>();
    
    // 存储节点ID和对应的WebSocket session映射
    private static final ConcurrentHashMap<Long, WebSocketSession> nodeSessions = new ConcurrentHashMap<>();

    // 节点会话生命周期锁。会话替换、上线/离线状态落库必须按节点串行,
    // 否则旧连接的关闭回调可能在新连接上线后把状态晚一步改回离线。
    // 锁对象按节点保留,避免删除后被并发路径重新创建出两把锁。
    private static final ConcurrentHashMap<Long, Object> nodeSessionLocks = new ConcurrentHashMap<>();

    /**
     * nodeId -> 该节点上的 sing-box 是否在运行(节点随系统信息一起上报)。
     *
     * gost 和 sing-box 是两个独立服务:sing-box 被停掉后 gost 照样活着、节点在面板里
     * 仍显示「在线」,但那台机上所有协议其实全都不可用 —— 这种状态不单独标出来,
     * 排查时会一直往协议参数上找原因(实战踩过,查了十几轮才发现服务根本没跑)。
     *
     * 只存内存:它是实时状态,面板重启后等节点下次上报即可(几秒到十几秒),
     * 没必要为此写库。null = 还没收到过上报(老节点或刚连上)。
     */
    private static final ConcurrentHashMap<Long, Boolean> singboxRunning = new ConcurrentHashMap<>();
    /** 这台机到底装没装 sing-box。老节点不报这个字段,取到 null 表示「不知道」 */
    private static final ConcurrentHashMap<Long, Boolean> singboxInstalled = new ConcurrentHashMap<>();
    /** sing-box 正在准备中 —— 刚建完协议那一两分钟就是这个状态,不该报红 */
    private static final ConcurrentHashMap<Long, Boolean> singboxInstalling = new ConcurrentHashMap<>();
    /** 上次安装失败的原因,空表示没失败过 */
    private static final ConcurrentHashMap<Long, String> singboxInstallErr = new ConcurrentHashMap<>();
    /** 节点实际安装的 sing-box 版本。项目兼容版和上游版由面板统一判断。 */
    private static final ConcurrentHashMap<Long, String> singboxVersions = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, String> singboxVersionErr = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Boolean> singboxUpdating = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, String> singboxUpdateErr = new ConcurrentHashMap<>();

    /** 取某节点的 sing-box 运行状态;null 表示未知(该节点还没上报过) */
    public static Boolean getSingboxInstalled(Long nodeId) {
        return nodeId == null ? null : singboxInstalled.get(nodeId);
    }

    public static Boolean getSingboxInstalling(Long nodeId) {
        return nodeId == null ? null : singboxInstalling.get(nodeId);
    }

    public static String getSingboxInstallErr(Long nodeId) {
        return nodeId == null ? null : singboxInstallErr.get(nodeId);
    }

    public static Boolean getSingboxRunning(Long nodeId) {
        return nodeId == null ? null : singboxRunning.get(nodeId);
    }

    public static String getSingboxVersion(Long nodeId) {
        return nodeId == null ? null : singboxVersions.get(nodeId);
    }

    public static String getSingboxVersionErr(Long nodeId) {
        return nodeId == null ? null : singboxVersionErr.get(nodeId);
    }

    public static Boolean getSingboxUpdating(Long nodeId) {
        return nodeId == null ? null : singboxUpdating.get(nodeId);
    }

    public static String getSingboxUpdateErr(Long nodeId) {
        return nodeId == null ? null : singboxUpdateErr.get(nodeId);
    }

    /** 节点连接失效后清掉实时 sing-box 状态,避免面板继续显示上一条连接的数据。 */
    private static void clearSingboxState(Long nodeId) {
        if (nodeId == null) {
            return;
        }
        singboxRunning.remove(nodeId);
        singboxInstalled.remove(nodeId);
        singboxInstalling.remove(nodeId);
        singboxInstallErr.remove(nodeId);
        singboxVersions.remove(nodeId);
        singboxVersionErr.remove(nodeId);
        singboxUpdating.remove(nodeId);
        singboxUpdateErr.remove(nodeId);
    }
    
    // 为每个session提供锁对象，防止并发发送消息
    private static final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();
    
    // 存储等待响应的请求。把目标 session 和 future 放在同一个对象里,
    // 这样节点断线时可以立即结束该连接上的所有请求,不会再等 10 秒超时。
    private static final ConcurrentHashMap<String, PendingRequest> pendingRequests = new ConcurrentHashMap<>();
    
    // 缓存加密器实例，避免重复创建
    private static final ConcurrentHashMap<String, AESCrypto> cryptoCache = new ConcurrentHashMap<>();

    private static final class PendingRequest {
        private final WebSocketSession session;
        private final CompletableFuture<GostDto> future;

        private PendingRequest(WebSocketSession session, CompletableFuture<GostDto> future) {
            this.session = session;
            this.future = future;
        }
    }

    private static Object nodeSessionLock(Long nodeId) {
        return nodeSessionLocks.computeIfAbsent(nodeId, key -> new Object());
    }

    /**
     * 加密消息包装器
     */
    public static class EncryptedMessage {
        private boolean encrypted;
        private String data;
        private Long timestamp;

        // getters and setters
        public boolean isEncrypted() { return encrypted; }
        public void setEncrypted(boolean encrypted) { this.encrypted = encrypted; }
        public String getData() { return data; }
        public void setData(String data) { this.data = data; }
        public Long getTimestamp() { return timestamp; }
        public void setTimestamp(Long timestamp) { this.timestamp = timestamp; }
    }

    //接受客户端消息
    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            if (StringUtils.isNoneBlank(message.getPayload())) {
                
                String id = session.getAttributes().get("id").toString();
                String type = session.getAttributes().get("type").toString();
                String nodeSecret = (String) session.getAttributes().get("nodeSecret");

                // 节点重连时旧连接可能还没来得及触发关闭回调。旧连接的最后一条
                // 系统信息不能覆盖新连接的实时状态,命令响应也不能混入新会话。
                if (Objects.equals(type, "1")) {
                    Long nodeId = Long.valueOf(id);
                    if (nodeSessions.get(nodeId) != session) {
                        log.debug("忽略节点 {} 旧 WebSocket 会话消息，sessionId: {}", nodeId, session.getId());
                        return;
                    }
                }

                // 尝试解密消息
                String decryptedPayload = decryptMessageIfNeeded(message.getPayload(), nodeSecret);

                if (decryptedPayload.contains("memory_usage")){
                    // 先发送确认消息
                    sendToUser(session, "{\"type\":\"call\"}", nodeSecret);
                }else if (decryptedPayload.contains("requestId")) {
                    // 处理命令响应消息
                    try {
                        JSONObject responseJson = JSONObject.parseObject(decryptedPayload);
                        String requestId = responseJson.getString("requestId");
                        String responseMessage = responseJson.getString("message");
                        String responseType = responseJson.getString("type");
                        JSONObject responseData = responseJson.getJSONObject("data");
                        
                        if (requestId != null) {
                            PendingRequest pendingRequest = pendingRequests.get(requestId);
                            boolean accepted = false;
                            if (pendingRequest != null && pendingRequest.session == session) {
                                if (Objects.equals(type, "1")) {
                                    Long nodeId = Long.valueOf(id);
                                    // 旧连接可能在首次身份检查之后才收到响应。
                                    // 会话确认与待响应移除必须在同一节点锁内完成,
                                    // 否则新连接替换期间仍可能消费旧会话的响应。
                                    synchronized (nodeSessionLock(nodeId)) {
                                        accepted = nodeSessions.get(nodeId) == session
                                                && pendingRequests.remove(requestId, pendingRequest);
                                    }
                                } else {
                                    accepted = pendingRequests.remove(requestId, pendingRequest);
                                }
                            }

                            if (accepted) {
                                log.info("收到节点响应，sessionId: {}, requestId: {}, type: {}",
                                        session.getId(), requestId, responseType);
                                GostDto result = new GostDto();
                                
                                // 根据响应类型处理不同的数据
                                if ("PingResponse".equals(responseType) && responseData != null) {
                                    // 特殊处理ping响应，将完整的响应数据返回
                                    result.setMsg(responseMessage != null ? responseMessage : "OK");
                                    result.setData(responseData); // 保存ping详细结果
                                } else {
                                    // 其他类型的响应
                                    result.setMsg(responseMessage != null ? responseMessage : "无响应消息");
                                    if (responseData != null) {
                                        result.setData(responseData);
                                    }
                                }
                                
                                pendingRequest.future.complete(result);
                            } else if (pendingRequest != null && pendingRequest.session != session) {
                                log.warn("忽略节点 {} 旧 WebSocket 会话的响应，requestId: {}", id, requestId);
                            }
                        }
                    } catch (Exception e) {
                        log.info("处理响应消息失败: {}", e.getMessage(), e);
                    }
                } else {
                    log.info("收到 WebSocket 消息，sessionId: {}, payloadLength: {}",
                            session.getId(), decryptedPayload == null ? 0 : decryptedPayload.length());
                }

                // 如果是节点类型，转发消息给其他会话
                if (Objects.equals(type, "1")) {
                    Long nodeId = Long.valueOf(id);
                    boolean shouldBroadcast = false;
                    boolean shouldSyncSingbox = false;
                    // 会话替换可能发生在解密期间。状态写入和最终身份检查必须在
                    // 同一节点锁内完成，避免旧连接的迟到上报覆盖新连接状态。
                    synchronized (nodeSessionLock(nodeId)) {
                        if (nodeSessions.get(nodeId) != session) {
                            log.debug("忽略节点 {} 旧 WebSocket 会话消息，sessionId: {}", nodeId, session.getId());
                            return;
                        }

                        // 顺手记下 sing-box 运行状态(节点在系统信息里带上来的)
                        try {
                            JSONObject info = JSON.parseObject(decryptedPayload);
                            if (info != null && info.containsKey("singbox_installed")) {
                                singboxInstalled.put(nodeId, info.getBooleanValue("singbox_installed"));
                            }
                            if (info != null && info.containsKey("singbox_installing")) {
                                singboxInstalling.put(nodeId, info.getBooleanValue("singbox_installing"));
                            }
                            if (info != null) {
                                // 失败原因用 put/remove 而不是只 put:装好之后这条要消失,
                                // 否则修好的机器会一直挂着上次的红字。
                                String err = info.getString("singbox_install_err");
                                if (err != null && !err.isEmpty()) {
                                    singboxInstallErr.put(nodeId, err);
                                } else {
                                    singboxInstallErr.remove(nodeId);
                                }
                            }
                            if (info != null && info.containsKey("singbox_running")) {
                                boolean running = info.getBooleanValue("singbox_running");
                                singboxRunning.put(nodeId, running);
                                if (!running && !Boolean.TRUE.equals(
                                        session.getAttributes().get("singboxSyncScheduled"))) {
                                    session.getAttributes().put("singboxSyncScheduled", true);
                                    shouldSyncSingbox = true;
                                }
                            }
                            if (info != null && info.containsKey("singbox_version")) {
                                String actualVersion = info.getString("singbox_version");
                                if (actualVersion != null && !actualVersion.trim().isEmpty()) {
                                    actualVersion = actualVersion.trim();
                                    String previousVersion = singboxVersions.put(nodeId, actualVersion);
                                    if (!actualVersion.equals(previousVersion)) {
                                        Node versionUpdate = new Node();
                                        versionUpdate.setId(nodeId);
                                        versionUpdate.setSingboxVersion(actualVersion);
                                        nodeService.updateById(versionUpdate);
                                    }
                                }
                            }
                            if (info != null) {
                                String versionErr = info.getString("singbox_version_err");
                                if (versionErr != null && !versionErr.isEmpty()) {
                                    singboxVersionErr.put(nodeId, versionErr);
                                } else {
                                    singboxVersionErr.remove(nodeId);
                                }
                            }
                            if (info != null && info.containsKey("singbox_updating")) {
                                singboxUpdating.put(nodeId, info.getBooleanValue("singbox_updating"));
                            }
                            if (info != null) {
                                String updateErr = info.getString("singbox_update_err");
                                if (updateErr != null && !updateErr.isEmpty()) {
                                    singboxUpdateErr.put(nodeId, updateErr);
                                } else {
                                    singboxUpdateErr.remove(nodeId);
                                }
                            }
                        } catch (Exception ignored) {
                            // 上报格式不对不影响广播,忽略
                        }
                        shouldBroadcast = true;
                    }

                    // 状态锁释放后新连接仍可能完成替换。再次确认当前会话，
                    // 避免旧连接在替换窗口内把迟到消息广播给管理员。
                    synchronized (nodeSessionLock(nodeId)) {
                        shouldBroadcast = shouldBroadcast && nodeSessions.get(nodeId) == session;
                    }
                    if (!shouldBroadcast) {
                        return;
                    }
                    if (shouldSyncSingbox) {
                        inboundService.syncNodeSingbox(nodeId);
                    }
                    JSONObject jsonObject = new JSONObject();
                    jsonObject.put("id", id);
                    jsonObject.put("type", "info");
                    jsonObject.put("data", decryptedPayload);
                    String broadcastMessage = jsonObject.toJSONString();
                    
                    // 异步处理广播消息，避免阻塞当前线程
                    for (WebSocketSession targetSession : activeSessions) {
                        if (targetSession != null && targetSession.isOpen() && !targetSession.equals(session)) {
                            sendToUser(targetSession, broadcastMessage, null);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.info("处理WebSocket消息时发生异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 尝试解密消息（如果需要）
     */
    private String decryptMessageIfNeeded(String payload, String nodeSecret) {
        if (payload == null || payload.trim().isEmpty()) {
            return payload;
        }

        try {
            // 尝试解析为加密消息格式
            EncryptedMessage encryptedMessage = JSON.parseObject(payload, EncryptedMessage.class);
            
            if (encryptedMessage.isEncrypted() && encryptedMessage.getData() != null) {
                // 获取或创建加密器
                AESCrypto crypto = getOrCreateCrypto(nodeSecret);
                if (crypto == null) {
                    log.info("⚠️ 收到加密消息但无法创建解密器，使用原始数据");
                    return payload;
                }
                
                // 解密数据
                String decryptedData = crypto.decryptString(encryptedMessage.getData());
                return decryptedData;
            }
        } catch (Exception e) {
            // 解析失败，可能是非加密格式，直接返回原始数据
            log.info("WebSocket消息未加密或解密失败，使用原始数据: {}", e.getMessage());
        }
        
        return payload;
    }

    /**
     * 加密消息（如果可能）
     */
    private static String encryptMessageIfPossible(String message, String nodeSecret) {
        if (message == null || nodeSecret == null) {
            return message;
        }

        try {
            AESCrypto crypto = getOrCreateCrypto(nodeSecret);
            if (crypto != null) {
                String encryptedData = crypto.encrypt(message);
                
                // 创建加密消息包装器
                JSONObject encryptedMessage = new JSONObject();
                encryptedMessage.put("encrypted", true);
                encryptedMessage.put("data", encryptedData);
                encryptedMessage.put("timestamp", System.currentTimeMillis());
                
                return encryptedMessage.toJSONString();
            }
        } catch (Exception e) {
            log.info("⚠️ WebSocket消息加密失败，发送原始数据: {}", e.getMessage());
        }

        return message;
    }

    /**
     * 获取或创建加密器实例
     */
    private static AESCrypto getOrCreateCrypto(String secret) {
        if (secret == null || secret.isEmpty()) {
            return null;
        }
        return cryptoCache.computeIfAbsent(secret, AESCrypto::create);
    }

    // 建立连接
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        try {
            String id = session.getAttributes().get("id").toString();
            String type = session.getAttributes().get("type").toString();
            
            if (!Objects.equals(type, "1")) {
                // 网页管理员连接
                activeSessions.add(session);
                log.info("管理员连接建立，sessionId: {}", session.getId());
            } else {
                // 客户端节点连接
                Long nodeId = Long.valueOf(id);
                String version = (String) session.getAttributes().get("nodeVersion");
                String http = (String) session.getAttributes().get("http");
                String tls = (String) session.getAttributes().get("tls");
                String socks = (String) session.getAttributes().get("socks");
                
                log.info("节点 {} 尝试连接，开始处理连接逻辑", nodeId);
                
                // 通过 compute 原子替换节点会话。不能拆成 get + put:
                // 两个并发的新连接可能交错执行，导致较早建立的连接反过来覆盖较晚连接。
                AtomicReference<WebSocketSession> replacedSessionRef = new AtomicReference<>();
                synchronized (nodeSessionLock(nodeId)) {
                    nodeSessions.compute(nodeId, (key, currentSession) -> {
                        if (currentSession != null && currentSession != session && currentSession.isOpen()) {
                            log.info("节点 {} 已有连接存在: {}，新连接将覆盖旧连接", nodeId, currentSession.getId());
                        }
                        if (currentSession != null && currentSession != session) {
                            replacedSessionRef.set(currentSession);
                        }
                        return session;
                    });
                }
                WebSocketSession existingSession = replacedSessionRef.get();

                // 覆盖映射后再关闭旧连接。cleanupSession 会沿用旧连接原有的发送锁，
                // 等正在发送的线程退出后才关闭并条件删除锁，避免同一 session 出现两把锁。
                if (existingSession != null && existingSession != session) {
                    log.info("主动关闭节点 {} 的旧连接: {}", nodeId, existingSession.getId());
                    cleanupSession(existingSession);
                }
                
                // 更新节点状态为在线。必须和当前会话检查放在同一把节点锁内,
                // 防止较早连接在较晚连接替换后继续写入过期版本/端口信息。
                boolean cleanupNewSession = false;
                String onlineBroadcast = null;
                synchronized (nodeSessionLock(nodeId)) {
                    if (nodeSessions.get(nodeId) != session) {
                        log.info("节点 {} 已被新连接替换，跳过旧连接上线状态更新", nodeId);
                    } else {
                        Node node = nodeService.getById(nodeId);
                        if (node != null) {
                            node.setStatus(1);
                            if (version != null) {
                                node.setVersion(version);
                            }
                            if (http != null) {
                                node.setHttp(Integer.parseInt(http));
                            }
                            if (tls != null) {
                                node.setTls(Integer.parseInt(tls));
                            }
                            if (socks != null) {
                                node.setSocks(Integer.parseInt(socks));
                            }

                            boolean updateResult = nodeService.updateById(node);
                            if (updateResult) {
                                log.info("节点 {} 连接建立成功，状态更新为在线，版本: {}", nodeId, version);

                                JSONObject res = new JSONObject();
                                res.put("id", id);
                                res.put("type", "status");
                                res.put("data", 1);
                                onlineBroadcast = res.toJSONString();
                            } else {
                                log.info("节点 {} 状态更新失败", nodeId);
                            }
                        } else {
                            log.info("节点 {} 不存在，无法更新状态", nodeId);
                            if (nodeSessions.remove(nodeId, session)) {
                                clearSingboxState(nodeId);
                            }
                            cleanupNewSession = true;
                        }
                    }
                }
                // 广播前在节点锁内再次确认当前会话。检查和发送必须保持有序:
                // 若刚释放上线落库锁就被下一条连接替换,旧连接不能晚一步广播状态。
                if (onlineBroadcast != null) {
                    broadcastNodeMessageIfCurrent(nodeId, session, onlineBroadcast);
                }
                if (cleanupNewSession) {
                    cleanupSession(session);
                }
            }

        } catch (Exception e) {
            log.info("建立连接时发生异常: {}", e.getMessage(), e);
            // 异常情况下，确保清理会话
            try {
                String id = session.getAttributes().get("id").toString();
                String type = session.getAttributes().get("type").toString();
                if (Objects.equals(type, "1")) {
                    Long nodeId = Long.valueOf(id);
                    String offlineBroadcast = null;
                    synchronized (nodeSessionLock(nodeId)) {
                        if (nodeSessions.remove(nodeId, session)) {
                            clearSingboxState(nodeId);

                            // 新连接已进入映射但上线初始化随后抛错时,关闭回调只会看到
                            // 映射已被移除。这里必须主动回收旧的在线状态。
                            try {
                                Node node = nodeService.getById(nodeId);
                                if (node != null) {
                                    node.setStatus(0);
                                    if (nodeService.updateById(node)) {
                                        JSONObject res = new JSONObject();
                                        res.put("id", id);
                                        res.put("type", "status");
                                        res.put("data", 0);
                                        offlineBroadcast = res.toJSONString();
                                    }
                                }
                            } catch (Exception statusException) {
                                log.info("异常连接回收节点 {} 离线状态失败: {}",
                                        nodeId, statusException.getMessage(), statusException);
                            }
                        }
                    }
                    if (offlineBroadcast != null) {
                        broadcastNodeMessageIfOffline(nodeId, offlineBroadcast);
                    }
                    cleanupSession(session);
                    log.info("由于异常，移除节点 {} 的会话", nodeId);
                }
            } catch (Exception cleanupException) {
                log.info("清理异常会话时出错: {}", cleanupException.getMessage());
            }
        }
    }

    // 连接关闭后
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        try {
            String id = session.getAttributes().get("id").toString();
            String type = session.getAttributes().get("type").toString();
            String sessionId = session.getId();
            
            log.info("连接关闭，ID: {}, 类型: {}, 状态: {}", id, type, status);
            failPendingRequestsForSession(session, "节点连接已断开");
            
            if (!Objects.equals(type, "1")) {
                // 管理员连接关闭
                boolean removed = activeSessions.remove(session);
                log.info("管理员连接关闭，sessionId: {}, 移除结果: {}", sessionId, removed);
            } else {
                // 客户端节点连接关闭
                Long nodeId = Long.valueOf(id);
                String offlineBroadcast = null;
                synchronized (nodeSessionLock(nodeId)) {
                    // 映射检查、删除和离线落库必须在同一临界区内完成。
                    // 新连接只能等离线处理结束后再替换,不会被旧回调覆盖。
                    WebSocketSession currentSession = nodeSessions.get(nodeId);
                    if (currentSession == null || currentSession != session) {
                        log.info("节点 {} 连接关闭，但已有新连接或会话不匹配，跳过状态更新", nodeId);
                    } else {
                        log.info("节点 {} 当前活跃连接关闭，开始验证并更新状态", nodeId);
                        if (nodeSessions.remove(nodeId, session)) {
                            clearSingboxState(nodeId);

                            Node node = nodeService.getById(nodeId);
                            if (node != null) {
                                node.setStatus(0);
                                boolean updateResult = nodeService.updateById(node);

                                if (updateResult) {
                                    log.info("节点 {} 状态更新为离线成功", nodeId);

                                    JSONObject res = new JSONObject();
                                    res.put("id", id);
                                    res.put("type", "status");
                                    res.put("data", 0);
                                    offlineBroadcast = res.toJSONString();
                                } else {
                                    log.info("节点 {} 状态更新为离线失败", nodeId);
                                }
                            } else {
                                log.info("节点 {} 不存在，无法更新离线状态", nodeId);
                            }
                        }
                    }
                }
                // 与上线路径相同,检查和广播按节点串行。新连接若已经建立,
                // 这条旧离线消息会被抑制,避免管理员页面最终显示成离线。
                if (offlineBroadcast != null) {
                    broadcastNodeMessageIfOffline(nodeId, offlineBroadcast);
                }
            }
            
        } catch (Exception e) {
            log.info("关闭连接时发生异常: {}", e.getMessage(), e);
        } finally {
            cleanupSession(session);
        }
    }

    // 点对点发送消息
    @SneakyThrows
    public static void sendToUser(WebSocketSession socketSession, String message) {
        sendToUser(socketSession, message, null);
    }

    // 点对点发送消息（支持加密）
    @SneakyThrows
    public static void sendToUser(WebSocketSession socketSession, String message, String nodeSecret) {
        if (socketSession == null) {
            return;
        }

        String sessionId = socketSession.getId();
        Object lock = sessionLocks.computeIfAbsent(sessionId, k -> new Object());
        boolean cleanup = false;

        synchronized (lock) {
            try {
                if (socketSession.isOpen()) {
                    // 如果是节点连接且有密钥，尝试加密消息
                    String finalMessage = message;
                    if (nodeSecret != null && !nodeSecret.isEmpty()) {
                        String type = (String) socketSession.getAttributes().get("type");
                        if ("1".equals(type)) { // 节点连接
                            finalMessage = encryptMessageIfPossible(message, nodeSecret);
                        }
                    }
                    socketSession.sendMessage(new TextMessage(finalMessage));
                } else {
                    cleanup = true;
                }
            } catch (Exception e) {
                log.info("发送WebSocket消息失败 [sessionId={}]: {}", sessionId, e.getMessage());
                cleanup = true;
            }
        }

        // 不要在发送锁内获取节点锁。关闭回调的锁顺序是节点锁 -> 发送锁，
        // 这里延后清理可避免发送失败时形成反向等待。
        if (cleanup) {
            cleanupSession(socketSession);
        }
    }
    
    /**
     * 清理失效的session，自动识别是节点session还是管理员session
     */
    private static void cleanupSession(WebSocketSession session) {
        if (session == null) return;

        String sessionId = session.getId();
        Object lock = sessionLocks.computeIfAbsent(sessionId, k -> new Object());

        boolean removedFromAdmin;
        synchronized (lock) {
            // 必须先让连接永久进入关闭状态，再从 map 删除锁。否则已取到旧锁的发送线程
            // 和删除后创建新锁的线程可能同时调用 sendMessage。
            if (session.isOpen()) {
                try {
                    session.close();
                } catch (Exception e) {
                    log.info("关闭失效 WebSocket 会话失败 [sessionId={}]: {}", sessionId, e.getMessage());
                }
            }

            failPendingRequestsForSession(session, "节点连接已断开");

            removedFromAdmin = activeSessions.remove(session);
        }

        Object type = session.getAttributes().get("type");
        if (!removedFromAdmin && "1".equals(String.valueOf(type))) {
            Long nodeId = null;
            Object id = session.getAttributes().get("id");
            if (id != null) {
                try {
                    nodeId = Long.valueOf(String.valueOf(id));
                } catch (NumberFormatException ignored) {
                    // 属性损坏时退回遍历映射进行清理
                }
            }
            if (nodeId != null) {
                synchronized (nodeSessionLock(nodeId)) {
                    if (nodeSessions.remove(nodeId, session)) {
                        clearSingboxState(nodeId);
                    }
                }
            } else {
                nodeSessions.forEach((candidateNodeId, currentSession) -> {
                    if (currentSession == session) {
                        synchronized (nodeSessionLock(candidateNodeId)) {
                            if (nodeSessions.remove(candidateNodeId, session)) {
                                clearSingboxState(candidateNodeId);
                            }
                        }
                    }
                });
            }
        }

        synchronized (lock) {
            // 只有 map 中仍是当前持有的锁时才删除，不能误删并发路径放入的新对象。
            sessionLocks.remove(sessionId, lock);
        }
    }

    /** 仅当 expectedSession 仍是节点当前连接时广播,并保持状态事件顺序。 */
    private static void broadcastNodeMessageIfCurrent(Long nodeId, WebSocketSession expectedSession, String message) {
        synchronized (nodeSessionLock(nodeId)) {
            if (nodeSessions.get(nodeId) == expectedSession) {
                broadcastMessage(message);
            }
        }
    }

    /** 仅当节点仍无连接时广播离线,避免重连后的在线事件被迟到离线事件覆盖。 */
    private static void broadcastNodeMessageIfOffline(Long nodeId, String message) {
        synchronized (nodeSessionLock(nodeId)) {
            if (!nodeSessions.containsKey(nodeId)) {
                broadcastMessage(message);
            }
        }
    }

    /** 节点断开时立即结束该 WebSocket 上的待处理命令,避免面板无意义地等待超时。 */
    private static void failPendingRequestsForSession(WebSocketSession session, String message) {
        if (session == null) {
            return;
        }
        pendingRequests.forEach((requestId, pendingRequest) -> {
            if (pendingRequest.session == session && pendingRequests.remove(requestId, pendingRequest)) {
                GostDto result = new GostDto();
                result.setMsg(message);
                pendingRequest.future.complete(result);
            }
        });
    }

    // 广播消息
    public static void broadcastMessage(String message) {
        for (WebSocketSession session : activeSessions) {
            sendToUser(session, message);
        }
    }



    public static GostDto send_msg(Long node_id, Object msg, String type) {
        GostDto result = new GostDto();
        if (node_id == null) {
            result.setMsg("节点不在线");
            return result;
        }

        WebSocketSession nodeSession;
        String requestId;
        CompletableFuture<GostDto> future;
        WebSocketSession closedSession = null;

        // 会话读取和待响应登记必须串行于节点替换/断开。
        // 否则可能先读到旧连接,等它被清理后才登记请求,导致 future 白等到超时。
        synchronized (nodeSessionLock(node_id)) {
            nodeSession = nodeSessions.get(node_id);

            if (nodeSession == null) {
                log.info("发送消息失败：节点 {} 不在线或会话不存在", node_id);
                result.setMsg("节点不在线");
                return result;
            }

            if (!nodeSession.isOpen()) {
                closedSession = nodeSession;
            }

            if (closedSession == null) {
                requestId = UUID.randomUUID().toString();
                future = new CompletableFuture<>();
                pendingRequests.put(requestId, new PendingRequest(nodeSession, future));
            } else {
                requestId = null;
                future = null;
            }
        }

        if (closedSession != null) {
            log.info("发送消息失败：节点 {} 连接已断开，清理会话", node_id);
            cleanupSession(closedSession);
            result.setMsg("节点连接已断开");
            return result;
        }
        
        // 获取节点密钥用于加密
        String nodeSecret = (String) nodeSession.getAttributes().get("nodeSecret");

        try {
            JSONObject data = new JSONObject();
            data.put("type", type);
            data.put("data", msg);
            data.put("requestId", requestId);
            sendToUser(nodeSession, data.toJSONString(), nodeSecret);
            result = future.get(10, TimeUnit.SECONDS);
            
            log.info("成功发送消息到节点 {} 并收到响应: {}", node_id, result.getMsg());
            return result;
            
        } catch (Exception e) {
            // 清理请求和映射关系
            pendingRequests.remove(requestId);

            result = new GostDto();
            if (e instanceof java.util.concurrent.TimeoutException) {
                result.setMsg("等待响应超时");
                log.info("节点 {} 响应超时，可能存在连接问题", node_id);
            } else {
                result.setMsg("发送消息失败: " + e.getMessage());
                log.info("发送消息到节点 {} 失败: {}", node_id, e.getMessage(), e);
            }
            return result;
        }
    }

    
}
