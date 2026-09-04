# TMS Agent 指南

## 语言与协作

- 所有面向用户的回答、分析、计划和进度说明使用中文。
- 不输出内部详细思维链，只给出结论、依据和可复现的验证结果。
- 修改前先检查 `git status`，保留工作区中已有的用户改动；不要使用破坏性 Git 命令覆盖或回退未知改动。

## 项目结构

- `springboot-backend/`：Spring Boot 面板后端、协议/中转/转发管理、WebSocket 节点控制和数据库迁移。
- `vite-frontend/`：Vite + React + TypeScript 管理面板和车友订阅页面。
- `go-gost/`：节点 Agent 与 GOST 运行时；容器镜像同时内置 sing-box。
- `.env.example`：生产面板仅用于覆盖 v6 Compose 的 IPv6 私有网段；数据库、JWT 和端口直接配置在生产 Compose 文件中，不得提交写入真实密钥的文件。
- `docker-compose.yml`：面板生产 Compose 默认入口，IPv4 bridge，直接拉取 GHCR 预构建镜像，不在 VPS 上构建前后端。
- `docker-compose-v4.yml` / `docker-compose-v6.yml`：面板生产 Compose 变体。v4 是显式 IPv4 配置，v6 仅在明确需要 Docker 内部 IPv6 且 daemon 已启用 IPv6 时使用。
- `docker-compose-node.yml`：独立节点 Compose，Agent 和 sing-box 共用 host network，配置持久化在节点目录的 `data/` 绑定目录。
- `docker-compose-hybrid.yml`：从源码构建面板的测试/联调配置，不作为生产升级入口。
- `panel_install.sh`：历史脚本部署的升级、状态查看、备份和卸载维护入口；新面板部署不得依赖该脚本。`install.sh`：面板生成的转发机一键安装命令及旧版裸机节点兼容入口。

## 部署与兼容约束

- 面板由 MySQL 8.0、后端和前端三个容器组成，支持 AMD64 和 ARM64。数据库绑定到面板目录的 `data/mysql/`，后端日志绑定到 `logs/backend/`；升级和重复执行安装不得删除这些宿主机目录。
- 历史 MySQL 5.7 数据目录升级到 8.0 前必须先导出 SQL 备份；8.0 完成数据字典升级后不能直接降回 5.7。`panel_install.sh` 检测到运行中的 5.7 时必须先备份，备份失败则终止升级。
- 生产面板 Compose 必须使用 GHCR 的预构建镜像，不能把 `build:` 或本地源码编译带入默认部署路径；`docker-compose-hybrid.yml` 仅用于测试/联调。
- `docker-compose.yml`、`docker-compose-v4.yml`、`docker-compose-v6.yml` 与 `.env.example` 的服务名、变量名、端口和绑定目录定义必须同步维护；修改默认部署变量时同时更新 README 和模板。
- 生产 Compose 使用宿主机绑定目录，不声明 named volume；IPv4 bridge 网络不要硬编码 `subnet`，依赖 Compose 服务名 DNS 和 Docker 自动地址分配，避免网段冲突。仅 v6 变体可通过 `TMS_IPV6_SUBNET` 指定私有 IPv6 网段，以适配未配置 IPv6 地址池的 Docker daemon。
- 历史面板从 named volume 升级到绑定目录时，`panel_install.sh` 必须先停止服务、复制数据并在失败时尝试恢复旧 Compose；不得直接切换到空目录。
- 面板 Compose 的 `BACKEND_PORT` 是节点连接面板的公网端口，IPv6 地址写入节点配置时必须使用 `[地址]:端口` 形式。
- 节点 Compose 依赖 Linux、Docker Engine 和 Compose 插件（或兼容的 `docker-compose`）；使用 host network 时不要添加普通端口映射，并提醒用户检查云安全组和主机防火墙。
- 节点首次启动从 `TMS_PANEL_ADDR`、`TMS_NODE_SECRET` 初始化 `/etc/gost/config.json`；重启时复用宿主机 `data/` 内配置。不要破坏裸机节点已有的 `config.json`、`gost.json`/`gost.yaml` 或 systemd 兼容路径。
- 面板“转发机监控”的安装按钮必须生成与上游相同形式的 `install.sh -a ... -s ...` 裸机一键命令，但下载地址使用 `PlanetSider/Tms` Release；`install.sh` 升级旧裸机节点时会停止旧的 `sing-box.service` 并交给 Agent 接管，修改 Agent 启停逻辑时必须同时验证这一迁移路径。
- 协议自动转发使用 `inbound-tunnel-node{nodeId}` 专用隧道，不要重新复用用户手工创建的端口转发隧道。
- 车友转发的 `client_link` 可能包含账号密码，会原文保存并进入通用“全部线路”订阅；接口校验和数据库迁移必须保持幂等。

## 开发与验证

- 前端改动后，在 `vite-frontend/` 执行 `npm run build`。
- 后端改动后，环境具备 Java/Maven 时在 `springboot-backend/` 执行 Maven 构建或测试；没有工具时在结果中明确说明未执行。
- Agent 改动后，环境具备 Go 时在 `go-gost/` 执行 `go test ./...` 或 `go build ./...`。
- Compose 或脚本改动后，至少运行 `git diff --check`；环境具备 Docker 时再运行对应的 `docker compose config`，并检查健康检查、变量校验、IPv4/IPv6 和绑定目录保留行为。
- 任何新增数据库列或表都要同时更新 `gost.sql` 与启动时的 `SchemaMigration`，并保证旧库可重复升级。
- 完成任务前检查 `git diff` 和 `git status --short`，最终说明已验证项目和未验证项目；除非用户明确要求，不自动提交或推送。
