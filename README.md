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

- `panel_install.sh` 支持安装、更新、状态查看、数据库导出、域名配置和卸载。
- 面板安装脚本会校验下载内容、检查镜像拉取失败并保留原服务，升级时不删除数据库和日志卷。
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

后端等待 MySQL 健康后启动，前端等待后端健康后启动。MySQL 使用 `mysql_data` 卷，后端日志使用 `backend_logs` 卷；`tms update` 和重复执行安装不会删除这两个卷。

### 节点使用单镜像和 host network

节点使用 `docker-compose-node.yml`，一个容器内同时运行 TMS Agent、GOST 和 sing-box：

- 使用 `network_mode: host`，保留面板下发的 TCP、UDP 和端口转发行为，不需要在 Compose 中逐项映射端口。
- 使用 `node_data` 卷保存 `/etc/gost/config.json`、GOST 配置、sing-box 配置和证书。
- 使用容器自带的 sing-box，不依赖宿主机安装 gost 或 sing-box 裸二进制。
- 使用自动重启、进程健康检查、较大的文件描述符上限和转发所需的网络能力。
- 节点镜像提供 amd64 和 arm64 架构；生产节点建议使用 Linux Docker Engine，Docker Desktop 的 host network 行为不等同于 Linux。

### Compose 文件

| 文件 | 场景 | 说明 |
|---|---|---|
| `docker-compose-v4.yml` | 面板生产部署 | 默认配置，Docker bridge 内部使用 IPv4 |
| `docker-compose-v6.yml` | 面板生产部署 | 设置 `TMS_IPV6=1` 后使用，需要 Docker daemon 支持 IPv6 |
| `docker-compose-node.yml` | 节点生产部署 | Agent + GOST + sing-box 单镜像，host network |
| `docker-compose-hybrid.yml` | 源码测试/联调 | 本地构建前后端镜像，不作为生产升级入口 |

### 旧裸机节点兼容

旧节点仍可使用 `install.sh` 安装 GOST systemd 服务。Agent 读取旧配置文件；升级旧版节点时会停止旧的 `sing-box.service`，将协议服务交给 Agent 管理。已经运行的裸机节点不需要为了使用新面板而立即重装，新的生产节点建议直接使用 Compose。

## 部署使用方法

### 1. 准备环境

面板机和节点机可以是不同服务器。

- 面板机：Linux、Docker Engine、Docker Compose 插件，以及访问 GitHub Container Registry（GHCR）的网络。
- 节点机：Linux、Docker Engine、Docker Compose 插件，支持 amd64 或 arm64；协议端口需要在主机防火墙和云安全组放行。
- 面板默认使用 TCP `6365` 供节点连接，使用 TCP `6366` 提供网页访问；如果端口被占用，安装脚本会自动选择附近的空闲端口。

### 2. 安装面板

在面板机执行：

~~~bash
mkdir -p /opt/tms-panel && cd /opt/tms-panel
curl -fsSL https://raw.githubusercontent.com/PlanetSider/Tms/main/panel_install.sh -o panel_install.sh
chmod +x panel_install.sh
./panel_install.sh
~~~

安装脚本会自动安装 Docker（系统未安装时）、下载 Compose 文件和数据库初始化脚本、生成随机数据库凭据和 JWT 密钥，并启动 MySQL、后端、前端三个容器。

安装结束后按脚本输出的地址访问面板。默认账号为 `admin_user`，默认密码为 `admin_user`，首次登录后必须立即修改密码。

面板配置保存在安装目录的 `.env` 中，常用变量如下：

~~~dotenv
DB_NAME=随机数据库名
DB_USER=随机数据库用户
DB_PASSWORD=随机强密码
JWT_SECRET=随机长字符串
FRONTEND_PORT=6366
BACKEND_PORT=6365
TMS_IPV6=0
~~~

确实需要 Docker 内部 IPv6 时，在首次安装前设置 `TMS_IPV6=1`：

~~~bash
TMS_IPV6=1 ./panel_install.sh
~~~

不要提交 .env，也不要把数据库密码、JWT 密钥和节点密钥发送到公开位置。

### 3. 添加节点

1. 登录面板，进入“转发机监控”并新增节点，填写节点 IP 或域名。
2. 保存节点后点击“安装”，复制面板生成的完整命令。
3. 在节点机执行该命令。命令会创建 `/opt/tms-node`，下载 `docker-compose-node.yml`，写入节点专属密钥，拉取节点镜像并启动容器。
4. 返回面板确认节点在线，再创建协议、线路或端口转发。

节点命令包含 `TMS_PANEL_ADDR` 和 `TMS_NODE_SECRET`，不要手动改用其他节点的密钥。节点 Compose 的常用操作：

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

### 5. 域名配置（可选）

面板域名和节点连接域名是两项独立配置。

给面板配置 HTTPS：

~~~bash
tms domain panel.example.com
~~~

域名必须解析到面板机，且 80、443 端口空闲并已在安全组放行。Caddy 会申请和续期 Let's Encrypt 证书；原来的 IP 加端口入口仍可作为备用入口。

给节点配置连接域名：在“转发机”编辑页面填写“连接域名”，订阅中的节点地址会优先使用该域名。域名只是替换显示的地址，DNS 解析仍可能暴露节点 IP；VLESS/Trojan Reality、Hysteria2 和 TUIC 也不适合通过普通 CDN 代理。

### 6. 更新和故障处理

更新面板：

~~~bash
tms update
tms status
~~~

更新节点：

~~~bash
cd /opt/tms-node
docker compose pull
docker compose up -d --force-recreate
~~~

更新不会删除面板数据库卷、日志卷或节点 node_data 卷。面板后端启动时会自动执行幂等数据库迁移。

如果节点无法拉取 ghcr.io/planetsider/tms-node:latest，先为 Docker 配置可用的网络出口或镜像源，再重新执行上面的 pull 和 up。节点端可用以下命令定位问题：

~~~bash
docker compose ps
docker compose logs --tail=200 node
~~~

面板显示节点在线只代表 Agent 在线；如果 sing-box 未运行，该节点上的协议仍不可用，应优先检查节点镜像是否拉取成功以及容器日志。

### 7. 卸载

面板机执行：

~~~bash
tms purge
~~~

该命令会删除面板容器、镜像、网络、数据卷和管理命令，数据库数据也会被删除。仅停止面板并保留数据时，在面板安装目录执行：

~~~bash
docker compose down
~~~

节点机执行 docker compose down 会保留 node_data；执行 docker compose down -v 才会删除节点配置、证书和运行时数据：

~~~bash
cd /opt/tms-node
docker compose down -v
~~~

面板和节点是两个独立的 Compose 项目，卸载一方不会自动删除另一方。

## 常用管理命令

| 命令 | 作用 |
|---|---|
| `tms` | 打开管理菜单 |
| `tms update` | 更新面板镜像和 Compose 配置 |
| `tms status` | 查看面板容器状态 |
| `tms info` | 查看面板访问地址和账号 |
| `tms domain DOMAIN` | 配置面板域名和 HTTPS |
| `tms domain` | 查看域名状态 |
| `tms domain off` | 关闭面板域名 |
| `tms export` | 导出数据库备份 |
| `tms purge` | 彻底卸载面板 |

## 免责声明

本项目仅供个人学习与研究使用，基于开源项目进行二次开发。

使用本项目所带来的任何风险均由使用者自行承担，包括但不限于服务异常、网络攻击、封禁、滥用、数据泄露、资源消耗和违反当地法律法规所产生的责任。

本项目为开源流量转发工具，仅限合法、合规用途。使用者必须确保其使用行为符合所在国家或地区的法律法规。禁止将本项目用于网络攻击、数据窃取、非法访问或任何未经授权的行为。

作者不对因使用本项目导致的任何法律责任、经济损失或其他后果承担责任，也不提供任何形式的担保或承诺。如不同意上述条款，请立即停止使用本项目。

---

[![Star History Chart](https://api.star-history.com/svg?repos=PlanetSider/Tms&type=Date)](https://www.star-history.com/#PlanetSider/Tms&Date)
