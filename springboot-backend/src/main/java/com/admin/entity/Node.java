package com.admin.entity;

import java.io.Serializable;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class Node extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private String name;

    private String secret;

    private String ip;

    private String serverIp;

    /**
     * 连接域名(可选)。填了就用它替代 server_ip 生成给车友的节点链接,
     * 这样车友在客户端里看到的是域名而不是车主的真实 IP。
     * 留空则沿用 server_ip。注意:域名只是不直接显示 IP,ping 一下还是查得到,
     * 要做到查不到得走 CDN。
     */
    private String domain;

    /**
     * 该节点上 sing-box 是否在运行(不入库,查询时从节点上报的实时状态填入)。
     * Agent 和 sing-box 是两个独立进程:sing-box 挂了 Agent 照样在线,
     * 面板不单独标出来的话,表现就是「节点显示在线但所有协议都连不上」。
     * null = 节点还没上报过(老版本节点或刚连上)。
     */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private Boolean singboxRunning;

    /**
     * 这台机装没装 sing-box。null = 节点版本较老、没上报过这个字段。
     * 所有节点均由 Agent 准备二进制。
     */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private Boolean singboxInstalled;

    /** sing-box 正在准备中。刚建完协议的那一两分钟就是这个状态,界面上该显示等待而不是报错 */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private Boolean singboxInstalling;

    /** 上次安装失败的原因(节点上报)。有值时直接显示给车主,省得上机器翻系统日志 */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String singboxInstallErr;

    /**
     * 当前是否存在启用中的协议入站。没有协议时 sing-box 保持停止是正常状态，
     * 前端只有在该值为 true 且进程未运行时才应告警。
     */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private Boolean singboxExpected;

    private String singboxVersion;

    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String singboxApprovedVersion;

    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String singboxUpstreamVersion;

    /** current / upstream_pending / update_required / incompatible / unknown */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String singboxVersionStatus;

    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private List<String> singboxAffectedProtocols;

    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String singboxUpdateSummary;

    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private Boolean singboxVersionCheckFailed;

    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private Long singboxVersionCheckedAt;

    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private Boolean singboxUpdating;

    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String singboxUpdateErr;

    private String version;

    private Integer portSta;

    private Integer portEnd;

    private Integer http;

    private Integer tls;

    private Integer socks;

}
