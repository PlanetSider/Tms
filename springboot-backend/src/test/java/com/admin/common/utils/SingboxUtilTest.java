package com.admin.common.utils;

import com.admin.entity.Inbound;
import com.admin.entity.InboundUser;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SingboxUtilTest {

    @Test
    void vmessInboundOmitsOutboundOnlyAlterIdField() {
        Inbound inbound = new Inbound();
        inbound.setTag("vmess-in");
        inbound.setProtocol("vmess");
        inbound.setListenPort(40000);

        InboundUser user = new InboundUser();
        user.setUuid("bf000d23-0752-40b4-affe-68f7707a9661");
        user.setStatus(1);

        JSONObject config = SingboxUtil.buildInbound(inbound, List.of(user));
        JSONArray users = config.getJSONArray("users");
        JSONObject generatedUser = users.getJSONObject(0);

        assertEquals(user.getUuid(), generatedUser.getString("uuid"));
        assertFalse(generatedUser.containsKey("alter_id"));
        assertFalse(generatedUser.containsKey("alterId"));
    }
}
