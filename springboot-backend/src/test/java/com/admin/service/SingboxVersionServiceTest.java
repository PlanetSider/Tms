package com.admin.service;

import com.admin.entity.Node;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SingboxVersionServiceTest {

    private final SingboxVersionService service = new SingboxVersionService();

    @Test
    void approvedNodeShowsKnownUpstreamChangesAsPending() {
        Node node = new Node();
        service.decorateNode(node, "1.13.12", "v1.14.0", false, 123L);

        assertEquals("upstream_pending", node.getSingboxVersionStatus());
        assertEquals("1.14.0", node.getSingboxUpstreamVersion());
        assertTrue(node.getSingboxAffectedProtocols().contains("hysteria2"));
        assertTrue(node.getSingboxAffectedProtocols().contains("tuic"));
        assertFalse(node.getSingboxAffectedProtocols().contains("vmess"));
    }

    @Test
    void olderNodeRequiresApprovedVersion() {
        Node node = new Node();
        service.decorateNode(node, "1.12.0", "1.14.0", false, 123L);

        assertEquals("update_required", node.getSingboxVersionStatus());
        assertEquals(SingboxVersionService.APPROVED_VERSION, node.getSingboxApprovedVersion());
        assertEquals(7, node.getSingboxAffectedProtocols().size());
    }

    @Test
    void newerUnapprovedNodeIsIncompatible() {
        Node node = new Node();
        service.decorateNode(node, "1.14.0", "1.14.0", false, 123L);

        assertEquals("incompatible", node.getSingboxVersionStatus());
        assertEquals(7, node.getSingboxAffectedProtocols().size());
    }

    @Test
    void versionReadErrorOverridesStoredVersion() {
        Node node = new Node();
        node.setSingboxVersionErr("读取 sing-box 版本失败");

        service.decorateNode(node, "1.13.12", "1.14.0", false, 123L);

        assertEquals("unknown", node.getSingboxVersionStatus());
        assertEquals("读取 sing-box 版本失败", node.getSingboxUpdateSummary());
    }

    @Test
    void comparesNumericVersions() {
        assertTrue(SingboxVersionService.compareVersions("1.14.0", "1.13.12") > 0);
        assertTrue(SingboxVersionService.compareVersions("1.13.2", "1.13.12") < 0);
        assertEquals(0, SingboxVersionService.compareVersions("v1.13.12", "1.13.12"));
    }

    @Test
    void compatibleUpgradePayloadContainsChecksumsForBothArchitectures() {
        JSONObject payload = service.buildCompatibleUpgradePayload();
        JSONObject checksums = payload.getJSONObject("checksums");

        assertEquals(SingboxVersionService.APPROVED_VERSION, payload.getString("version"));
        assertEquals(64, checksums.getString("amd64").length());
        assertEquals(64, checksums.getString("arm64").length());
    }
}
