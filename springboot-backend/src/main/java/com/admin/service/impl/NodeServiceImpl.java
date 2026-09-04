package com.admin.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.admin.common.dto.GostDto;
import com.admin.common.dto.NodeDto;
import com.admin.common.dto.NodeUpdateDto;
import com.admin.common.lang.R;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Inbound;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.entity.ViteConfig;
import com.admin.mapper.InboundMapper;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.TunnelMapper;
import com.admin.service.NodeService;
import com.admin.service.SingboxVersionService;
import com.admin.service.TunnelService;
import com.admin.service.ViteConfigService;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;

/**
 * <p>
 * 节点服务实现类
 * 提供节点的增删改查功能，包括节点创建、更新、删除和查询操作
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@Service
public class NodeServiceImpl extends ServiceImpl<NodeMapper, Node> implements NodeService {

    // ========== 常量定义 ==========
    
    /** 节点默认状态：启用 */
    private static final int NODE_STATUS_ACTIVE = 0;
    
    /** 成功响应消息 */
    private static final String SUCCESS_CREATE_MSG = "节点创建成功";
    private static final String SUCCESS_UPDATE_MSG = "节点更新成功";
    private static final String SUCCESS_DELETE_MSG = "节点删除成功";
    
    /** 错误响应消息 */
    private static final String ERROR_CREATE_MSG = "节点创建失败";
    private static final String ERROR_UPDATE_MSG = "节点更新失败";
    private static final String ERROR_DELETE_MSG = "节点删除失败";
    private static final String ERROR_NODE_NOT_FOUND = "节点不存在";
    
    /** 隧道使用检查相关消息 */
    private static final String ERROR_IN_NODE_IN_USE = "该节点还有 %d 个隧道作为入口节点在使用，请先删除相关隧道";
    private static final String ERROR_OUT_NODE_IN_USE = "该节点还有 %d 个隧道作为出口节点在使用，请先删除相关隧道";
    
    /** 端口范围验证相关消息 */
    private static final String ERROR_PORT_STA_REQUIRED = "起始端口不能为空";
    private static final String ERROR_PORT_END_REQUIRED = "结束端口不能为空";
    private static final String ERROR_PORT_RANGE_INVALID = "端口必须在1-65535范围内";
    private static final String ERROR_PORT_ORDER_INVALID = "结束端口不能小于起始端口";

    // ========== 依赖注入 ==========
    
    @Resource
    private TunnelMapper tunnelMapper;

    @Resource
    private InboundMapper inboundMapper;

    @Resource
    private SingboxVersionService singboxVersionService;

    @Resource
    @Lazy
    private TunnelService tunnelService;

    @Resource
    ViteConfigService viteConfigService;


    // ========== 公共接口实现 ==========

    /**
     * 创建新节点
     * 
     * @param nodeDto 节点创建数据传输对象
     * @return 创建结果响应
     */
    @Override
    public R createNode(NodeDto nodeDto) {
        Node node = buildNewNode(nodeDto);
        boolean result = this.save(node);
        return result ? R.ok(SUCCESS_CREATE_MSG) : R.err(ERROR_CREATE_MSG);
    }



    /**
     * 获取所有节点列表
     * 注意：返回结果中会隐藏节点密钥信息
     * 
     * @return 包含所有节点的响应对象
     */
    @Override
    public R getAllNodes() {
        List<Node> nodeList = this.list();
        hideNodeSecrets(nodeList);
        Set<Long> nodesWithActiveInbounds = new HashSet<>();
        for (Inbound inbound : inboundMapper.selectList(
                new QueryWrapper<Inbound>().select("node_id").eq("status", 1))) {
            if (inbound.getNodeId() != null) {
                nodesWithActiveInbounds.add(inbound.getNodeId());
            }
        }
        // 带上节点上报的 sing-box 运行状态,让前端能区分「节点在线」和「协议可用」
        for (Node n : nodeList) {
            n.setSingboxRunning(com.admin.common.utils.WebSocketServer.getSingboxRunning(n.getId()));
            n.setSingboxInstalled(com.admin.common.utils.WebSocketServer.getSingboxInstalled(n.getId()));
            n.setSingboxInstalling(com.admin.common.utils.WebSocketServer.getSingboxInstalling(n.getId()));
            n.setSingboxInstallErr(com.admin.common.utils.WebSocketServer.getSingboxInstallErr(n.getId()));
            n.setSingboxExpected(nodesWithActiveInbounds.contains(n.getId()));
            n.setSingboxUpdating(com.admin.common.utils.WebSocketServer.getSingboxUpdating(n.getId()));
            n.setSingboxUpdateErr(com.admin.common.utils.WebSocketServer.getSingboxUpdateErr(n.getId()));
            String reportedSingboxVersion = com.admin.common.utils.WebSocketServer.getSingboxVersion(n.getId());
            singboxVersionService.decorateNode(n,
                    reportedSingboxVersion != null ? reportedSingboxVersion : n.getSingboxVersion());
        }
        return R.ok(nodeList);
    }

    /**
     * 更新节点信息
     * 
     * @param nodeUpdateDto 节点更新数据传输对象
     * @return 更新结果响应
     */
    @Override
    public R updateNode(NodeUpdateDto nodeUpdateDto) {
        // 1. 验证节点是否存在
        Node node = this.getById(nodeUpdateDto.getId());
        if (node == null) {
            return R.err(ERROR_NODE_NOT_FOUND);
        }

        //1.1 如果节点在线 且传入更新的 http/tls/socks 任意一项与数据库不一致，则通过 WS 通知节点更新设置
        boolean online = node.getStatus() != null && node.getStatus() == 1;
        Integer newHttp = nodeUpdateDto.getHttp();
        Integer newTls = nodeUpdateDto.getTls();
        Integer newSocks = nodeUpdateDto.getSocks();

        boolean httpChanged = newHttp != null && !newHttp.equals(node.getHttp());
        boolean tlsChanged = newTls != null && !newTls.equals(node.getTls());
        boolean socksChanged = newSocks != null && !newSocks.equals(node.getSocks());

        if (online && (httpChanged || tlsChanged || socksChanged)) {
            JSONObject req = new JSONObject();
            req.put("http", newHttp);
            req.put("tls", newTls);
            req.put("socks", newSocks);

            GostDto gostResult = WebSocketServer.send_msg(node.getId(), req, "SetProtocol");
            if (!Objects.equals(gostResult.getMsg(), "OK")){
                return R.err(gostResult.getMsg());
            }
        }


        // 2. 构建更新对象并执行更新
        Node updateNode = buildUpdateNode(nodeUpdateDto);
        boolean result = this.updateById(updateNode);

        // 更新隧道入口ip
        List<Tunnel> inNodeId = tunnelService.list(new QueryWrapper<Tunnel>().eq("in_node_id", updateNode.getId()));
        if (!inNodeId.isEmpty()) {
            for (Tunnel tunnel : inNodeId) {
                tunnel.setInIp(updateNode.getIp());
            }
            tunnelService.updateBatchById(inNodeId);
        }

        // 更新服务器出口ip
        List<Tunnel> outNodeId = tunnelService.list(new QueryWrapper<Tunnel>().eq("out_node_id", updateNode.getId()));
        if (!outNodeId.isEmpty()) {
            for (Tunnel tunnel : outNodeId) {
                tunnel.setOutIp(updateNode.getServerIp());
            }
            tunnelService.updateBatchById(outNodeId);
        }

        return result ? R.ok(SUCCESS_UPDATE_MSG) : R.err(ERROR_UPDATE_MSG);
    }

    /**
     * 删除节点
     * 删除前会检查是否有隧道正在使用该节点
     * 
     * @param id 节点ID
     * @return 删除结果响应
     */
    @Override
    public R deleteNode(Long id) {
        // 1. 验证节点是否存在
        Node node = this.getById(id);
        if (node == null) {
            return R.err(ERROR_NODE_NOT_FOUND);
        }

        // 2. 检查节点使用情况
        R usageCheckResult = checkNodeUsage(id);
        if (usageCheckResult.getCode() != 0) {
            return usageCheckResult;
        }

        // 3. 执行删除操作
        boolean result = this.removeById(id);
        return result ? R.ok(SUCCESS_DELETE_MSG) : R.err(ERROR_DELETE_MSG);
    }

    /**
     * 根据ID获取节点信息
     * 
     * @param id 节点ID
     * @return 节点对象
     * @throws RuntimeException 当节点不存在时抛出异常
     */
    @Override
    public Node getNodeById(Long id) {
        Node node = this.getById(id);
        if (node == null) {
            throw new RuntimeException(ERROR_NODE_NOT_FOUND);
        }
        return node;
    }

    // ========== 私有辅助方法 ==========

    /**
     * 构建新节点对象
     * 
     * @param nodeDto 节点创建DTO
     * @return 构建完成的节点对象
     */
    private Node buildNewNode(NodeDto nodeDto) {
        Node node = new Node();
        BeanUtils.copyProperties(nodeDto, node);
        
        // 验证端口范围
        validatePortRange(node.getPortSta(), node.getPortEnd());
        
        // 设置默认属性
        node.setSecret(IdUtil.simpleUUID());
        node.setStatus(NODE_STATUS_ACTIVE);
        
        // 设置时间戳
        long currentTime = System.currentTimeMillis();
        node.setCreatedTime(currentTime);
        node.setUpdatedTime(currentTime);
        
        return node;
    }

    /**
     * 构建节点更新对象
     * 
     * @param nodeUpdateDto 节点更新DTO
     * @return 构建完成的更新对象
     */
    private Node buildUpdateNode(NodeUpdateDto nodeUpdateDto) {
        Node node = new Node();
        node.setId(nodeUpdateDto.getId());
        node.setName(nodeUpdateDto.getName());
        node.setIp(nodeUpdateDto.getIp());
        node.setServerIp(nodeUpdateDto.getServerIp());
        node.setDomain(nodeUpdateDto.getDomain());
        node.setPortSta(nodeUpdateDto.getPortSta());
        node.setPortEnd(nodeUpdateDto.getPortEnd());
        node.setHttp(nodeUpdateDto.getHttp());
        node.setTls(nodeUpdateDto.getTls());
        node.setSocks(nodeUpdateDto.getSocks());
        // 验证端口范围
        validatePortRange(node.getPortSta(), node.getPortEnd());
        
        node.setUpdatedTime(System.currentTimeMillis());
        return node;
    }

    /**
     * 隐藏节点列表中的密钥信息
     * 
     * @param nodeList 节点列表
     */
    private void hideNodeSecrets(List<Node> nodeList) {
        nodeList.forEach(node -> node.setSecret(null));
    }


    /**
     * 检查节点使用情况
     * 验证是否有隧道正在使用该节点作为入口或出口节点
     * 
     * @param nodeId 节点ID
     * @return 检查结果响应
     */
    private R checkNodeUsage(Long nodeId) {
        // 检查入口节点使用情况
        R inNodeCheckResult = checkInNodeUsage(nodeId);
        if (inNodeCheckResult.getCode() != 0) {
            return inNodeCheckResult;
        }

        // 检查出口节点使用情况
        return checkOutNodeUsage(nodeId);
    }

    /**
     * 检查节点作为入口节点的使用情况
     * 
     * @param nodeId 节点ID
     * @return 检查结果响应
     */
    private R checkInNodeUsage(Long nodeId) {
        QueryWrapper<Tunnel> query = new QueryWrapper<>();
        query.eq("in_node_id", nodeId);
        
        long tunnelCount = tunnelMapper.selectCount(query);
        if (tunnelCount > 0) {
            String errorMsg = String.format(ERROR_IN_NODE_IN_USE, tunnelCount);
            return R.err(errorMsg);
        }
        
        return R.ok();
    }

    /**
     * 检查节点作为出口节点的使用情况
     * 
     * @param nodeId 节点ID
     * @return 检查结果响应
     */
    private R checkOutNodeUsage(Long nodeId) {
        QueryWrapper<Tunnel> query = new QueryWrapper<>();
        query.eq("out_node_id", nodeId);
        
        long tunnelCount = tunnelMapper.selectCount(query);
        if (tunnelCount > 0) {
            String errorMsg = String.format(ERROR_OUT_NODE_IN_USE, tunnelCount);
            return R.err(errorMsg);
        }
        
        return R.ok();
    }

    /**
     * 获取节点安装命令
     * 根据节点信息生成对应的安装命令
     * 
     * @param id 节点ID
     * @return 包含安装命令的响应对象
     */
    @Override
    public R getInstallCommand(Long id) {
        // 1. 验证节点是否存在
        Node node = this.getById(id);
        if (node == null) {
            return R.err(ERROR_NODE_NOT_FOUND);
        }

        // 2. 构建安装命令
        return buildInstallCommand(node);
    }

    @Override
    public R updateSingbox(Long id) {
        Node node = this.getById(id);
        if (node == null) {
            return R.err(ERROR_NODE_NOT_FOUND);
        }
        if (node.getStatus() == null || node.getStatus() != 1) {
            return R.err("节点不在线");
        }

        String actualVersion = WebSocketServer.getSingboxVersion(id);
        if (actualVersion == null) {
            actualVersion = node.getSingboxVersion();
        }
        Node versionState = new Node();
        singboxVersionService.decorateNode(versionState, actualVersion);
        String status = versionState.getSingboxVersionStatus();
        if ("current".equals(status)) {
            return R.err("当前已是项目兼容版 " + versionState.getSingboxApprovedVersion());
        }
        if ("upstream_pending".equals(status)) {
            return R.err("当前已是项目兼容版；上游新版本仍在适配验证中");
        }
        if ("incompatible".equals(status)) {
            return R.err("节点版本高于项目兼容版，禁止自动降级");
        }
        if (!"update_required".equals(status)) {
            return R.err("无法识别节点 sing-box 版本，请先更新 Agent 并等待状态上报");
        }

        GostDto result = WebSocketServer.send_msg(id,
                singboxVersionService.buildCompatibleUpgradePayload(), "UpdateSingbox");
        if (result == null || !"OK".equals(result.getMsg())) {
            String message = result == null ? "节点无响应" : result.getMsg();
            if (message != null && message.contains("未知命令类型")) {
                message = "节点 Agent 不支持在线升级，请先使用安装脚本更新 Agent";
            }
            return R.err("启动协议升级失败: " + message);
        }

        JSONObject response = new JSONObject();
        response.put("targetVersion", versionState.getSingboxApprovedVersion());
        return R.ok(response);
    }

    /**
     * 构建节点安装命令
     * 
     * @param node 节点对象
     * @return 格式化的安装命令
     */
    private R buildInstallCommand(Node node) {
        ViteConfig viteConfig = viteConfigService.getOne(new QueryWrapper<ViteConfig>().eq("name", "ip"));
        if (viteConfig == null) return R.err("请先前往网站配置中设置ip");
        if (StrUtil.isBlank(viteConfig.getValue())) return R.err("面板地址不能为空，请先前往网站配置中设置ip");
        String panelAddressError = validateInstallValue(viteConfig.getValue(), "面板地址");
        if (panelAddressError != null) return R.err(panelAddressError);
        panelAddressError = validatePanelAddress(viteConfig.getValue());
        if (panelAddressError != null) return R.err(panelAddressError);
        String nodeSecretError = validateInstallValue(node.getSecret(), "节点密钥");
        if (nodeSecretError != null) return R.err(nodeSecretError);

        // 处理服务器地址，如果是IPv6需要添加方括号
        String processedServerAddr = processServerAddress(viteConfig.getValue());

        // 与上游保持相同的一键安装方式，但从本仓库 Release 下载兼容版节点脚本。
        String installUrl = "https://github.com/PlanetSider/Tms/releases/latest/download/install.sh";
        StringBuilder command = new StringBuilder();
        command.append("curl -L ")
               .append(shellQuote(installUrl))
               .append(" -o ./install.sh && chmod +x ./install.sh && ./install.sh")
               .append(" -a ").append(shellQuote(processedServerAddr))
               .append(" -s ").append(shellQuote(node.getSecret()));

        return R.ok(command.toString());
    }

    /** 将任意值安全地放入 POSIX shell 单引号字符串中。 */
    private String shellQuote(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    /** 安装命令会交给 shell 执行，拒绝包含换行的动态参数。 */
    private String validateInstallValue(String value, String fieldName) {
        if (value != null && (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)) {
            return fieldName + "不能包含换行符";
        }
        return null;
    }

    /**
     * 校验节点连接面板所需的地址格式。
     * 网站配置历史上允许填写裸 host:port;新版也支持 http/https URL。
     * 带协议的域名可以省略端口,由 http/https/ws/wss 分别使用默认端口。
     */
    private String validatePanelAddress(String serverAddr) {
        String address = serverAddr.trim();
        int schemeEnd = address.indexOf("://");
        boolean hasScheme = schemeEnd > 0;
        if (schemeEnd > 0) {
            String scheme = address.substring(0, schemeEnd);
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)
                    && !"ws".equalsIgnoreCase(scheme) && !"wss".equalsIgnoreCase(scheme)) {
                return "面板地址只支持 http://、https://、ws:// 或 wss:// 协议";
            }
            address = address.substring(schemeEnd + 3);
        }

        int pathStart = firstIndexOf(address, '/', '?', '#');
        if (pathStart >= 0) {
            String suffix = address.substring(pathStart);
            if (!"/".equals(suffix)) {
                return "面板地址不支持路径、查询参数或片段,请填写域名或 IP:端口";
            }
            address = address.substring(0, pathStart);
        }
        if (address.isEmpty()) {
            return "面板地址格式无效,请填写 IP:端口、域名:端口或 [IPv6]:端口";
        }

        if (address.startsWith("[")) {
            int closeBracket = address.indexOf(']');
            if (closeBracket <= 1 || closeBracket != address.lastIndexOf(']')) {
                return "IPv6 面板地址格式无效,请填写 [IPv6]:端口";
            }
            String host = address.substring(1, closeBracket);
            String suffix = address.substring(closeBracket + 1);
            if (!isIPv6Address(host) || (!suffix.isEmpty() && !suffix.startsWith(":"))) {
                return "IPv6 面板地址格式无效,请填写 [IPv6]:端口";
            }
            if (!suffix.isEmpty() && !isValidPort(suffix.substring(1))) {
                return "面板地址端口必须在1-65535范围内";
            }
            if (suffix.isEmpty() && !hasScheme) {
                return "裸 IPv6 面板地址必须包含端口,例如 [2001:db8::1]:6365";
            }
            return null;
        }

        if (address.indexOf(']') >= 0 || address.indexOf('[') >= 0 || address.indexOf('@') >= 0) {
            return "面板地址格式无效,请填写 IP:端口、域名:端口或 [IPv6]:端口";
        }

        long colonCount = address.chars().filter(ch -> ch == ':').count();
        if (colonCount == 0) {
            if (hasScheme && isValidPanelHost(address)) {
                return null;
            }
            return "面板地址必须包含端口,例如 example.com:6365";
        }
        if (colonCount == 1) {
            int colon = address.indexOf(':');
            String host = address.substring(0, colon);
            String port = address.substring(colon + 1);
            if (!isValidPanelHost(host)) {
                return "面板地址主机格式无效";
            }
            if (!isValidPort(port)) {
                return "面板地址端口必须在1-65535范围内";
            }
            return null;
        }

        // 兼容历史的未加方括号 IPv6:port,下发时会自动规范成 [IPv6]:port。
        int lastColon = address.lastIndexOf(':');
        String host = address.substring(0, lastColon);
        String port = address.substring(lastColon + 1);
        if (isIPv6Address(host) && isValidPort(port)) {
            return null;
        }
        if (isIPv6Address(address)) {
            return hasScheme
                    ? null
                    : "IPv6 面板地址必须包含端口,例如 [2001:db8::1]:6365";
        }
        return "面板地址格式无效,请填写 IP:端口、域名:端口或 [IPv6]:端口";
    }

    private boolean isValidPanelHost(String host) {
        return !host.isEmpty() && host.matches("[A-Za-z0-9._-]+");
    }

    /**
     * 处理服务器地址，确保IPv6地址被方括号包裹
     * 
     * @param serverAddr 原始服务器地址，格式可能为 host:port
     * @return 处理后的服务器地址
     */
    private String processServerAddress(String serverAddr) {
        if (StrUtil.isBlank(serverAddr)) {
            return serverAddr;
        }
        String address = serverAddr.trim();

        // 网站配置历史上既可能保存 host:port,也可能保存完整 URL。
        // 保留协议头,让节点可以通过 HTTPS/WSS 连接 Caddy 入口。
        String scheme = "";
        int schemeEnd = address.indexOf("://");
        if (schemeEnd > 0) {
            scheme = address.substring(0, schemeEnd).toLowerCase();
            address = address.substring(schemeEnd + 3);
        }
        int pathStart = firstIndexOf(address, '/', '?', '#');
        if (pathStart >= 0) {
            address = address.substring(0, pathStart);
        }

        // 已经是 [IPv6]:port 或 [IPv6],不要重复包裹。
        if (address.startsWith("[")) {
            int closeBracket = address.indexOf(']');
            if (closeBracket > 0) {
                return scheme.isEmpty() ? address : scheme + "://" + address;
            }
        }

        // 兼容旧配置中的 IPv6:port。只有端口有效且前半段是 IPv6 时才拆分,
        // 纯 IPv6 地址则在下面统一补方括号。
        if (address.chars().filter(ch -> ch == ':').count() > 1) {
            int lastColon = address.lastIndexOf(':');
            if (lastColon > 0) {
                String host = address.substring(0, lastColon);
                String port = address.substring(lastColon + 1);
                if (isIPv6Address(host) && isValidPort(port)) {
                    String result = "[" + host + "]:" + port;
                    return scheme.isEmpty() ? result : scheme + "://" + result;
                }
            }
        }

        // 判断整个字符串是不是完整的 IPv6,避免把 ::1 误拆成 [:]:1。
        // 未加方括号的纯 IPv6 地址只补方括号。
        if (isIPv6Address(address)) {
            String result = "[" + address + "]";
            return scheme.isEmpty() ? result : scheme + "://" + result;
        }
        return scheme.isEmpty() ? address : scheme + "://" + address;
    }

    private int firstIndexOf(String value, char... chars) {
        int index = -1;
        for (char ch : chars) {
            int candidate = value.indexOf(ch);
            if (candidate >= 0 && (index < 0 || candidate < index)) {
                index = candidate;
            }
        }
        return index;
    }

    private boolean isValidPort(String port) {
        if (port == null || !port.matches("[0-9]{1,5}")) {
            return false;
        }
        try {
            int value = Integer.parseInt(port);
            return value > 0 && value <= 65535;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    /**
     * 判断是否为IPv6地址
     * 
     * @param address 地址字符串（不包含端口号）
     * @return 是否为IPv6地址
     */
    private boolean isIPv6Address(String address) {
        // IPv6地址包含多个冒号，至少2个；再用 JDK 的字面量解析排除普通字符串。
        if (!address.contains(":")) {
            return false;
        }

        long colonCount = address.chars().filter(ch -> ch == ':').count();
        if (colonCount < 2) {
            return false;
        }
        try {
            return InetAddress.getByName(address) instanceof Inet6Address;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 验证端口范围的有效性
     * 
     * @param portSta 起始端口
     * @param portEnd 结束端口
     * @throws RuntimeException 当端口范围无效时抛出异常
     */
    private void validatePortRange(Integer portSta, Integer portEnd) {
        // 检查起始端口是否为空
        if (portSta == null) {
            throw new RuntimeException(ERROR_PORT_STA_REQUIRED);
        }
        
        // 检查结束端口是否为空
        if (portEnd == null) {
            throw new RuntimeException(ERROR_PORT_END_REQUIRED);
        }
        
        // 检查端口范围是否在有效区间内
        if (portSta < 1 || portSta > 65535 || portEnd < 1 || portEnd > 65535) {
            throw new RuntimeException(ERROR_PORT_RANGE_INVALID);
        }
        
        // 检查端口顺序是否正确
        if (portEnd < portSta) {
            throw new RuntimeException(ERROR_PORT_ORDER_INVALID);
        }
    }

}
