export type SingboxVersionStatus =
  | "current"
  | "upstream_pending"
  | "update_required"
  | "incompatible"
  | "unknown";

export interface SingboxVersionFields {
  /** 节点内核名称；未提供时使用当前项目默认的 sing-box。 */
  coreName?: string;
  singboxVersion?: string;
  singboxVersionErr?: string;
  singboxApprovedVersion?: string;
  singboxUpstreamVersion?: string;
  singboxVersionStatus?: SingboxVersionStatus;
  singboxAffectedProtocols?: string[];
  singboxUpdateSummary?: string;
  singboxVersionCheckFailed?: boolean;
  singboxVersionCheckedAt?: number;
}

type ChipColor = "default" | "primary" | "secondary" | "success" | "warning" | "danger";

export const protocolVersionVisual = (
  node: SingboxVersionFields,
  protocol?: string,
): { color: ChipColor; label?: string } => {
  const normalized = (protocol || "").trim().toLowerCase();
  const affected = node.singboxAffectedProtocols || [];
  if (!affected.includes(normalized)) {
    return { color: "secondary" };
  }
  if (node.singboxVersionStatus === "upstream_pending") {
    return { color: "warning", label: "待适配" };
  }
  if (node.singboxVersionStatus === "update_required") {
    return { color: "danger", label: "需升级" };
  }
  if (node.singboxVersionStatus === "incompatible") {
    return { color: "danger", label: "未兼容" };
  }
  return { color: "secondary" };
};
