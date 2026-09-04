import { Chip } from "@heroui/chip";

export type SingboxVersionStatus =
  | "current"
  | "upstream_pending"
  | "update_required"
  | "incompatible"
  | "unknown";

export interface SingboxVersionFields {
  singboxVersion?: string;
  singboxApprovedVersion?: string;
  singboxUpstreamVersion?: string;
  singboxVersionStatus?: SingboxVersionStatus;
  singboxAffectedProtocols?: string[];
  singboxUpdateSummary?: string;
  singboxVersionCheckFailed?: boolean;
  singboxVersionCheckedAt?: number;
}

type ChipColor = "default" | "primary" | "secondary" | "success" | "warning" | "danger";

const versionBadge = (node: SingboxVersionFields): { color: ChipColor; label: string } => {
  if (node.singboxVersionStatus === "upstream_pending") {
    return {
      color: "warning",
      label: node.singboxUpstreamVersion ? `上游 ${node.singboxUpstreamVersion} 待适配` : "上游待适配",
    };
  }
  if (node.singboxVersionStatus === "update_required") {
    return {
      color: "danger",
      label: node.singboxApprovedVersion ? `需升级至 ${node.singboxApprovedVersion}` : "需升级",
    };
  }
  if (node.singboxVersionStatus === "incompatible") {
    return { color: "danger", label: "版本未兼容" };
  }
  if (node.singboxVersionStatus === "current") {
    return node.singboxVersionCheckFailed
      ? { color: "default", label: "上游检查失败" }
      : { color: "secondary", label: "项目兼容版" };
  }
  return { color: "default", label: "版本未知" };
};

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

export function SingboxVersionBadge({ node }: { node: SingboxVersionFields }) {
  const badge = versionBadge(node);
  return (
    <div
      className="flex min-w-0 items-center justify-end gap-1.5"
      title={node.singboxUpdateSummary || undefined}
    >
      <span className="font-mono text-xs">{node.singboxVersion || "未知"}</span>
      <Chip color={badge.color} size="sm" variant="flat" className="text-[10px]">
        {badge.label}
      </Chip>
    </div>
  );
}
