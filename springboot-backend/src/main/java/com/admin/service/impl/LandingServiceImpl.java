package com.admin.service.impl;

import com.admin.common.dto.GostDto;
import com.admin.common.dto.LandingDto;
import com.admin.common.lang.R;
import com.admin.common.utils.LandingUtil;
import com.admin.common.utils.SingboxUtil;
import com.admin.entity.Inbound;
import com.admin.entity.Landing;
import com.admin.entity.Node;
import com.admin.mapper.InboundMapper;
import com.admin.mapper.LandingMapper;
import com.admin.mapper.NodeMapper;
import com.admin.service.LandingService;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * 落地服务实现。
 *
 * @author QAQ
 * @since 2026-07-23
 */
@Service
public class LandingServiceImpl extends ServiceImpl<LandingMapper, Landing> implements LandingService {

    @Resource
    private InboundMapper inboundMapper;
    @Resource
    private NodeMapper nodeMapper;

    @Override
    public R createLanding(LandingDto dto) {
        LandingUtil.Parsed parsed;
        try {
            parsed = LandingUtil.parse(dto.getLink());
        } catch (IllegalArgumentException e) {
            return R.err(e.getMessage());
        }
        Landing landing = new Landing();
        landing.setName(dto.getName());
        landing.setType(parsed.type);
        landing.setLink(dto.getLink().trim());
        landing.setOutboundJson(parsed.outbound.toJSONString());
        landing.setRemark(dto.getRemark());
        landing.setStatus(1);
        landing.setCreatedTime(System.currentTimeMillis());
        landing.setUpdatedTime(System.currentTimeMillis());
        if (!this.save(landing)) {
            return R.err("落地保存失败");
        }
        return R.ok(landing);
    }

    @Override
    public R getLandings() {
        List<Landing> list = this.list(new QueryWrapper<Landing>().orderByDesc("id"));
        return R.ok(list);
    }

    @Override
    public R deleteLanding(Long id) {
        long used = inboundMapper.selectCount(new QueryWrapper<Inbound>().eq("landing_id", id));
        if (used > 0) {
            return R.err("这条落地正在被 " + used + " 个中转协议使用,先清空对应机器的中转再删");
        }
        this.removeById(id);
        return R.ok();
    }

    @Override
    public R testLanding(Long nodeId, String link) {
        LandingUtil.Parsed parsed;
        try {
            parsed = LandingUtil.parse(link);
        } catch (IllegalArgumentException e) {
            return R.err(e.getMessage());
        }
        // 协议落地(ss/vless/tuic/anytls…)暂不在线测,格式已校验即算通过
        if (!"socks5".equals(parsed.type)) {
            JSONObject r = new JSONObject();
            r.put("ok", true);
            r.put("skipped", true);
            r.put("type", parsed.type);
            r.put("msg", parsed.type + " 落地格式已校验(协议落地暂不支持在线测试,可直接保存)");
            return R.ok(r);
        }
        Node node = nodeMapper.selectById(nodeId);
        if (node == null) {
            return R.err("前置机不存在");
        }
        GostDto g = SingboxUtil.TestOutbound(nodeId, parsed.outbound);
        if (g == null || !"OK".equals(g.getMsg())) {
            return R.err("经前置机测落地不通:" + (g != null && g.getMsg() != null ? g.getMsg() : "节点无响应/超时"));
        }
        JSONObject data = JSONObject.parseObject(JSONObject.toJSONString(g.getData()));
        data.put("type", parsed.type);
        return R.ok(data); // {ok, exitIp, latencyMs, type}
    }
}
