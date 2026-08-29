package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;

/**
 * 新建协议入站(合体面板)。支持 VLESS/Trojan-Reality、VMess、Shadowsocks-2022、Hysteria2、TUIC、AnyTLS。
 */
@Data
public class InboundDto {

    @NotNull(message = "节点不能为空")
    private Long nodeId;

    /** 协议:vless / trojan / vmess / shadowsocks / hysteria2 / tuic / anytls */
    private String protocol;

    /** sing-box 本机监听口,可空(自动分配 40000+) */
    private Integer listenPort;

    /** Reality 借用的 SNI(仅 vless-reality 需要,如 www.microsoft.com) */
    private String sni;

    /** Reality 握手目标站点,可空则用 sni */
    private String dest;

    /** 落地ID:空=直连(协议管理),有=中转(该入站经此落地出网) */
    private Long landingId;

    private String remark;
}
