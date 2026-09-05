import { Chip } from "@heroui/chip";

export type SingboxVersionStatus =
  | "current"
  | "upstream_pending"
  | "update_required"
  | "incompatible"
  | "unknown";

export interface SingboxVersionFields {
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

const versionBadge = (node: SingboxVersionFields): { color: ChipColor; label: string } => {
  if (node.singboxVersionErr) {
    return { color: "danger", label: "读取失败" };
  }
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
  const approvedVersion = node.singboxApprovedVersion || "未知";
  const upstreamVersion = node.singboxUpstreamVersion
    || (node.singboxVersionCheckFailed ? "检查失败" : "未知");
  const currentVersion = node.singboxVersionErr
    ? "读取失败"
    : node.singboxVersion || "未知";

  return (
    <div
      className="flex min-w-0 items-start justify-end gap-2"
      title={node.singboxVersionErr || node.singboxUpdateSummary || undefined}
    >
      <div className="min-w-0 space-y-0.5 text-right text-[11px] leading-4">
        <div className="whitespace-nowrap">
          <span className="text-default-500">项目兼容版：</span>
          <span className="font-mono">{approvedVersion}</span>
        </div>
        <div className="whitespace-nowrap">
          <span className="text-default-500">上游最新版：</span>
          <span className="font-mono">{upstreamVersion}</span>
        </div>
        <div className="whitespace-nowrap">
          <span className="text-default-500">节点当前版：</span>
          <span className={node.singboxVersionErr ? "text-danger" : "font-mono"}>{currentVersion}</span>
        </div>
      </div>
      <Chip color={badge.color} size="sm" variant="flat" className="shrink-0 text-[10px]">
        {badge.label}
      </Chip>
    </div>
  );
}
