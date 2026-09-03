# TMS

TMS 是一个集中管理协议节点、端口转发和用户订阅的面板。面板负责保存配置、用户权限、流量和到期信息，节点负责实际的 TCP/UDP 转发与 sing-box 协议服务。

## 引用来源

本项目是在以下开源项目基础上的二次开发和集成：

| 来源 | 用途 |
|---|---|
| [Teminuosi/Tms](https://github.com/Teminuosi/Tms) | 上游 TMS 面板实现、原有面板功能和协议管理逻辑 |
| [PlanetSider/Tms](https://github.com/PlanetSider/Tms) | 本仓库维护的 Docker Compose 发行版本和部署脚本 |
| [go-gost/gost](https://github.com/go-gost/gost) | TCP/UDP 转发、隧道、限速和流量统计 |
| [go-gost/x](https://github.com/go-gost/x) | GOST 扩展服务、API、WebSocket 和配置能力 |
| [SagerNet/sing-box](https://github.com/SagerNet/sing-box) | VLESS-Reality、Trojan-Reality、VMess、Shadowsocks-2022、Hysteria2、TUIC、AnyTLS 协议服务 |
| [Docker Compose](https://docs.docker.com/compose/) | 面板和节点的容器编排与生命周期管理 |

上游项目和依赖项目分别遵循其原有许可证；本仓库的许可证见 [LICENSE](LICENSE)。

## 项目改动

相对于上游版本，本仓库主要完成了以下功能和兼容性改造：

### 面板与协议

- 支持 VLESS-Reality、Trojan-Reality、VMess、Shadowsocks-2022、Hysteria2、TUIC、AnyTLS。
- 支持直连线路、落地中转、普通端口转发和隧道转发。
- 支持按用户、线路和转发分别设置限速、流量配额和到期时间。
- 支持为已有普通端口转发复制副本并分配给车友，副本拥有独立端口、限速、流量和到期状态。
- 支持为车友转发保存客户端分享链接，并将有效链接加入通用“全部线路”订阅。
- 支持通用订阅和 Clash/Mihomo YAML 订阅，线路停用、到期或转发暂停后，刷新订阅即可移除对应项目。
- 协议自动中转使用 `inbound-tunnel-node{nodeId}` 专用隧道，避免与用户手工创建的转发隧道冲突。

### 节点 Agent

- Agent 支持通过 `TMS_PANEL_ADDR` 和 `TMS_NODE_SECRET` 自动初始化配置。
- 保留旧裸机节点的 `config.json`、`gost.json`/`gost.yaml` 和 systemd 使用方式。
- Agent 负责 sing-box 的安装、配置、启动、停止、崩溃恢复和状态上报。
- 节点重启或容器重建后会从持久化配置恢复，不需要重新在面板创建节点。
- 支持 IPv4、IPv6 和带 `http/https/ws/wss` 协议的面板地址；IPv6 地址使用 `[地址]:端口` 格式。

### WebSocket 与后端

- 面板和节点的 WebSocket 请求使用 `requestId` 绑定，响应不会串到其他请求。
- 同一节点的连接建立、替换、关闭和在线状态更新按节点串行处理，旧连接的迟到消息不会覆盖新连接。
- 节点断开时，绑定在该连接上的请求立即结束，不再无意义等待超时。
- 增加启动时幂等数据库迁移，旧数据库升级时自动补齐协议、线路、聚合订阅和车友转发所需字段。
- 转发复制分配使用源转发行锁，避免并发操作产生重复副本；发现孤儿转发记录时会自动修复关联关系。

### 脚本与发布

- 新面板安装不依赖 `.sh`，直接使用 `docker-compose.yml` 拉取 GHCR 预构建镜像。
- `panel_install.sh` 仅保留给已经使用脚本部署的面板做升级、备份和卸载；脚本不会在新部署主路径中使用。
- `install.sh` 保留旧版裸机节点入口，并在升级时处理旧 `sing-box` systemd 服务与 Agent 的交接。
- GitHub Actions 负责面板镜像、节点镜像和相关发布流程。

## Compose 化的主要修改

### 面板由三个容器组成

面板生产部署拆分为以下服务：

| 服务 | 作用 | 默认端口 |
|---|---|---:|
| `mysql` | 保存用户、节点、协议、转发和订阅数据 | 仅容器网络 |
| `backend` | Spring Boot API、WebSocket 和定时任务 | `6365` |
| `frontend` | 管理页面和订阅页面 | `6366` |

后端等待 MySQL 健康后启动，前端等待后端健康后启动。MySQL 数据直接绑定到 `./data/mysql/`，后端日志绑定到 `./logs/backend/`；更新和重复执行 Compose 不会删除这些宿主机目录。

### 节点使用单镜像和 host network

节点使用 `docker-compose-node.yml`，一个容器内同时运行 TMS Agent、GOST 和 sing-box：

- 使用 `network_mode: host`，保留面板下发的 TCP、UDP 和端口转发行为，不需要在 Compose 中逐项映射端口。
- 使用节点目录下的 `./data/` 绑定目录保存 `/etc/gost/config.json`、GOST 配置、sing-box 配置和证书。
- 使用容器自带的 sing-box，不依赖宿主机安装 gost 或 sing-box 裸二进制。
- 使用自动重启、进程健康检查、较大的文件描述符上限和转发所需的网络能力。
- 节点镜像提供 amd64 和 arm64 架构；生产节点建议使用 Linux Docker Engine，Docker Desktop 的 host network 行为不等同于 Linux。

### Compose 文件

| 文件 | 场景 | 说明 |
|---|---|---|
| `docker-compose.yml` | 面板生产部署（默认） | IPv4 bridge、配置直接写入文件、宿主机目录绑定，直接拉取 GHCR 预构建镜像 |
| `docker-compose-v4.yml` | 面板生产部署 | 显式 IPv4 bridge、配置直接写入文件、宿主机目录绑定 |
| `docker-compose-v6.yml` | 面板生产部署 | 启用 Docker IPv6、配置直接写入文件、宿主机目录绑定，需要 daemon 支持 IPv6；网段可用 `TMS_IPV6_SUBNET` 覆盖 |
| `docker-compose-node.yml` | 节点生产部署 | Agent + GOST + sing-box 单镜像、`./data` 绑定、host network |
| `docker-compose-hybrid.yml` | 源码测试/联调 | 本地构建前后端镜像、宿主机目录绑定，不作为生产升级入口 |

生产 Compose 不声明 Docker named volume。IPv4 bridge 不指定固定网段，面板容器通过 Compose 内置 DNS 使用 `mysql`、`backend` 等服务名通信，Docker 自动分配的网络地址即可满足需求；不固定 `172.20.0.0/16` 可避免与宿主机 VPN、云网络或其他 Compose 项目冲突。v6 变体保留可覆盖的私有 IPv6 网段，因为部分 Docker daemon 在启用 IPv6 时必须显式提供地址池；如不需要容器内部 IPv6，直接使用默认 `docker-compose.yml`。

### 裸机节点兼容

面板“转发机监控”中的安装命令使用 `install.sh` 安装 GOST systemd 服务，操作方式与上游一致，但脚本和节点程序从 `PlanetSider/Tms` Release 下载。Agent 读取旧配置文件；升级旧版节点时会停止旧的 `sing-box.service`，将协议服务交给 Agent 管理。已经运行的裸机节点不需要为了使用新面板而立即重装；需要容器化节点时仍可手动使用 `docker-compose-node.yml`。

## 部署使用方法

### 1. 准备环境

面板机和节点机可以是不同服务器。

- 面板机：Linux、Docker Engine、Docker Compose 插件，以及访问 GitHub Container Registry（GHCR）的网络。
- 节点机：Linux、Docker Engine、Docker Compose 插件，支持 amd64 或 arm64；协议端口需要在主机防火墙和云安全组放行。
- 面板默认使用 TCP `6365` 供节点连接，使用 TCP `6366` 提供网页访问；Compose 不会自动改端口，端口被占用时请修改所用 Compose 文件中带 `TMS_BACKEND_PORT` 或 `TMS_FRONTEND_PORT` 注释的端口映射。

### 2. 使用 Compose 安装面板

新部署不需要在 VPS 上安装或运行任何 TMS `.sh` 安装脚本，也不需要在 VPS 上编译前后端镜像。Compose 会直接拉取 GitHub Actions 自动构建并发布到 GHCR 的镜像。

在面板机执行：

~~~bash
mkdir -p /opt/tms-panel && cd /opt/tms-panel
git clone https://github.com/PlanetSider/Tms.git .
mkdir -p data/mysql logs/backend
~~~

编辑准备使用的 Compose 文件顶部配置，至少填写数据库密码和 JWT 密钥。默认使用 `docker-compose.yml`：

~~~yaml
x-tms-config:
  db-name: &tms-db-name "gost" # TMS_DB_NAME
  db-user: &tms-db-user "gost" # TMS_DB_USER
  db-password: &tms-db-password "换成随机强密码" # TMS_DB_PASSWORD
  jwt-secret: &tms-jwt-secret "换成随机长字符串" # TMS_JWT_SECRET
~~~

数据库密码和 JWT 密钥默认为空，未填写时 MySQL 或后端不会正常启动。端口直接写在 `backend`、`frontend` 服务的 `ports` 中，默认分别为 `6365`、`6366`。

启动预构建镜像：

~~~bash
docker compose pull
docker compose up -d
docker compose ps
~~~

默认访问地址为 `http://面板IP:6366`，默认账号和密码均为 `admin_user`，首次登录后必须立即修改密码。`BACKEND_PORT` 是节点连接面板的公网端口，必须在安全组和防火墙放行。

公开 GHCR 镜像无需登录即可拉取；如果仓库管理员将镜像设为私有，先使用拥有 `read:packages` 权限的 GitHub 账号执行 `docker login ghcr.io`，再运行 `docker compose pull`。

如果不想完整 clone 仓库，也可以只下载 Compose 和数据库文件；下载后同样先填写 Compose 顶部的密码和 JWT 密钥：

~~~bash
mkdir -p /opt/tms-panel && cd /opt/tms-panel
curl -fsSL https://raw.githubusercontent.com/PlanetSider/Tms/main/docker-compose.yml -o docker-compose.yml
curl -fsSL https://raw.githubusercontent.com/PlanetSider/Tms/main/gost.sql -o gost.sql
mkdir -p data/mysql logs/backend
~~~

确实需要 Docker 内部 IPv6 时，下载或 clone `docker-compose-v6.yml`，在该文件顶部填写同样的配置后启动：

~~~bash
docker compose -f docker-compose-v6.yml pull
docker compose -f docker-compose-v6.yml up -d
~~~

v6 文件默认使用 `fd00:dead:beef::/48`；确实发生网段冲突时，可在 `.env` 中设置 `TMS_IPV6_SUBNET` 覆盖。不要提交写入真实密码或 JWT 密钥的 Compose 文件，也不要把数据库密码、JWT 密钥和节点密钥发送到公开位置。

### 3. 添加节点

1. 登录面板，进入“转发机监控”并新增节点，填写节点 IP 或域名。
2. 保存节点后点击“安装”，复制面板生成的完整命令。
3. 在节点机执行该命令。命令会从本仓库 Release 下载 `install.sh`，写入节点专属的面板地址和密钥，并将节点 Agent 安装为 systemd 服务。
4. 返回面板确认节点在线，再创建协议、线路或端口转发。

安装命令包含面板地址和 `TMS_NODE_SECRET`，不要公开命令或改用其他节点的密钥。需要改用容器化节点时，可在节点机准备 `docker-compose-node.yml` 和 `.env`，其中填写 `TMS_PANEL_ADDR`、`TMS_NODE_SECRET`；常用操作如下：

~~~bash
cd /opt/tms-node
docker compose ps
docker compose logs -f node
docker compose pull
docker compose up -d --force-recreate
docker compose down
~~~

节点使用 host network，因此 Compose 文件不写端口映射；仍需在节点机放行面板下发的协议端口和转发端口。

### 4. 配置协议和订阅

在“协议管理”中选择节点并创建协议。Reality、端口、密钥和自签证书由面板生成；创建后配置通过 WebSocket 下发到节点，节点 Agent 会启动或重载 sing-box。

管理员可以在协议或中转页面将整条线路分配给车友，也可以在“转发”页面把已有普通端口转发复制给车友：

1. 点击“分配”，选择车友、限速策略和到期日期。
2. 提交后面板会自动分配独立入口端口并下发转发服务。
3. 可选填写客户端分享链接，保存后会进入该车友的通用“全部线路”订阅。

分享链接接口为 `POST /api/v1/forward/set-link`：

~~~json
{"forwardId": 123, "link": "socks5://user:password@example.com:443"}
~~~

将 `link` 设置为 `null` 或空字符串可以清除链接。链接最大 1024 字符，不能包含空白或控制字符，支持 `vless`、`vmess`、`trojan`、`ss`、`hysteria2`/`hy2`、`tuic`、`anytls`、`socks`/`socks5` scheme。链接可能含有账号密码，面板会按原文保存到数据库，请只向可信用户分发。

车友可以在“我的订阅”复制通用订阅或 Clash/Mihomo 订阅。两种格式不通用：v2rayN、小火箭、v2rayNG 使用通用订阅，Clash Verge、ClashMeta、Mihomo 使用 Clash/Mihomo 订阅。

### 5. 域名和 HTTPS（可选）

面板域名和节点连接域名是两项独立配置。

纯 Compose 面板默认通过 TCP `6366` 提供 HTTP。需要域名和 HTTPS 时，请在面板机前置配置 Caddy、Nginx 或云负载均衡，将域名反向代理到 `127.0.0.1:6366`，并放行 80、443 端口。反向代理配置不由默认 Compose 自动创建，原来的 IP 加端口入口仍可作为备用入口。

已经使用旧 `panel_install.sh` 部署的用户仍可通过 `tms domain DOMAIN` 管理脚本创建的 Caddy 配置；新 Compose 部署不要执行该命令。

给节点配置连接域名：在“转发机”编辑页面填写“连接域名”，订阅中的节点地址会优先使用该域名。域名只是替换显示的地址，DNS 解析仍可能暴露节点 IP；VLESS/Trojan Reality、Hysteria2 和 TUIC 也不适合通过普通 CDN 代理。

### 6. 更新和故障处理

更新面板（Compose 部署）：

~~~bash
cd /opt/tms-panel
docker compose pull
docker compose up -d --force-recreate
docker compose ps
~~~

数据库密码和 JWT 密钥现在直接保存在 Compose 中。更新 Compose 模板前先备份当前文件，更新后把原来的 `x-tms-config` 值和端口映射填回，再执行 `docker compose up`；不要用空白模板覆盖正在运行的配置。只下载文件的部署方式还需同步更新 `gost.sql`。旧脚本部署用户仍可使用 `tms update`，脚本会自动保留原凭据和端口；不要将该命令用于手动 Compose 部署。

更新节点：

~~~bash
cd /opt/tms-node
docker compose pull
docker compose up -d --force-recreate
~~~

更新不会删除面板 `data/mysql/`、`logs/backend/` 或节点 `data/` 目录。面板后端启动时会自动执行幂等数据库迁移。

从旧版 named volume 部署升级时，先停止旧服务并迁移一次数据，再使用新的绑定目录。通过历史 `panel_install.sh update` 或重复安装时，脚本会自动完成面板两个 named volume 的迁移；直接替换 Compose 文件的用户按下面命令手动迁移：

~~~bash
cd /opt/tms-panel
docker compose down
mkdir -p data/mysql logs/backend
docker run --rm -v mysql_data:/from:ro -v "$PWD/data/mysql":/to alpine sh -c 'cp -a /from/. /to/'
docker run --rm -v backend_logs:/from:ro -v "$PWD/logs/backend":/to alpine sh -c 'cp -a /from/. /to/'
docker compose up -d
~~~

旧节点如果使用 `node_data` named volume，先记录实际卷名（`docker volume ls --format '{{.Name}}' | grep node_data`），再执行以下迁移；将示例中的 `旧节点卷名` 替换为查询结果：

~~~bash
cd /opt/tms-node
docker compose down
mkdir -p data
docker run --rm -v 旧节点卷名:/from:ro -v "$PWD/data":/to alpine sh -c 'cp -a /from/. /to/'
docker compose up -d
~~~

迁移完成并确认服务正常后，旧 named volume 可以手动删除。

如果节点无法拉取 ghcr.io/planetsider/tms-node:latest，先为 Docker 配置可用的网络出口或镜像源，再重新执行上面的 pull 和 up。节点端可用以下命令定位问题：

~~~bash
docker compose ps
docker compose logs --tail=200 node
~~~

面板显示节点在线只代表 Agent 在线；如果 sing-box 未运行，该节点上的协议仍不可用，应优先检查节点镜像是否拉取成功以及容器日志。

### 7. 卸载

面板机执行（Compose 部署）：

~~~bash
cd /opt/tms-panel
docker compose down
~~~

`docker compose down` 会停止并删除面板容器和网络，但保留宿主机数据库与日志目录。确认已完成备份、要删除数据库数据和日志时，再执行：

~~~bash
rm -rf data/mysql logs/backend
~~~

旧脚本部署用户仍可使用 `tms purge`；该命令会连同脚本管理的资源一起清理。

节点机执行 `docker compose down` 会保留 `data/`；确认要删除节点配置、证书和运行时数据时，再删除该目录：

~~~bash
cd /opt/tms-node
docker compose down
rm -rf data
~~~

面板和节点是两个独立的 Compose 项目，卸载一方不会自动删除另一方。

## 常用 Compose 管理命令

| 命令 | 作用 |
|---|---|
| `docker compose ps` | 查看面板容器状态和健康状态 |
| `docker compose logs -f backend` | 查看后端实时日志 |
| `docker compose logs -f frontend` | 查看前端实时日志 |
| `docker compose pull` | 拉取 GitHub Actions 发布的最新镜像 |
| `docker compose up -d --force-recreate` | 应用镜像或 Compose 配置更新 |
| `docker compose restart backend` | 仅重启后端容器 |
| `docker compose down` | 停止面板并保留宿主机数据目录 |
| `rm -rf data/mysql logs/backend` | 删除面板数据库和日志（请先确认备份） |

以上命令均需在面板 Compose 目录（例如 `/opt/tms-panel`）执行。绑定目录由 Compose 文件所在目录决定，不要在其他目录执行同一文件。旧脚本部署用户的 `tms`、`tms update`、`tms status`、`tms info`、`tms domain`、`tms export` 和 `tms purge` 命令继续保留兼容，不适用于新 Compose 部署。

## 免责声明

本项目仅供个人学习与研究使用，基于开源项目进行二次开发。

使用本项目所带来的任何风险均由使用者自行承担，包括但不限于服务异常、网络攻击、封禁、滥用、数据泄露、资源消耗和违反当地法律法规所产生的责任。

本项目为开源流量转发工具，仅限合法、合规用途。使用者必须确保其使用行为符合所在国家或地区的法律法规。禁止将本项目用于网络攻击、数据窃取、非法访问或任何未经授权的行为。

作者不对因使用本项目导致的任何法律责任、经济损失或其他后果承担责任，也不提供任何形式的担保或承诺。如不同意上述条款，请立即停止使用本项目。

---

[![Star History Chart](https://api.star-history.com/svg?repos=PlanetSider/Tms&type=Date)](https://www.star-history.com/#PlanetSider/Tms&Date)
