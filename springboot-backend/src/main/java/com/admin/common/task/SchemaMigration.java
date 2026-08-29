package com.admin.common.task;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 启动时的表结构自动迁移。
 *
 * gost.sql 只在【新装】时执行一次,已经装好的面板 Compose 更新只换镜像、不动表结构。
 * 所以往实体里加字段必须配一次 ALTER,否则老用户一查就 Unknown column,面板直接崩。
 *
 * 迁移只做加法:列不存在就加、表不存在就建,不改动现有业务数据。所有操作幂等、
 * 可重复执行,失败也只记日志不影响启动 ——
 * 迁移挂了顶多是新功能不可用,不能连带把整个面板拖死。
 */
@Slf4j
@Component
@Order(1)
public class SchemaMigration implements ApplicationRunner {

    @Resource
    private DataSource dataSource;

    @Override
    public void run(ApplicationArguments args) {
        // 转发机的「连接域名」:填了就用它生成节点链接,车友看到的是域名而不是车主的 IP
        addColumnIfMissing("node", "domain",
                "ALTER TABLE `node` ADD COLUMN `domain` VARCHAR(255) NULL COMMENT '连接域名(可选,留空用 server_ip)'");

        // 协议/中转功能是在已有裸机版本之后加入的。MySQL 的 initdb 脚本只会在
        // 空数据目录执行,所以这里也要补齐合体 schema,否则更新镜像后旧库会在第一次
        // 访问 inbound/landing 时直接报 Unknown table/column。
        addColumnIfMissing("node", "cert_mode",
                "ALTER TABLE `node` ADD COLUMN `cert_mode` INT(10) NOT NULL DEFAULT 0 COMMENT '0=无域名(Reality/自签) 1=有域名TLS'");
        addColumnIfMissing("node", "cert_path",
                "ALTER TABLE `node` ADD COLUMN `cert_path` VARCHAR(500) NULL COMMENT '有域名时证书路径'");
        addColumnIfMissing("node", "key_path",
                "ALTER TABLE `node` ADD COLUMN `key_path` VARCHAR(500) NULL COMMENT '有域名时私钥路径'");

        createHybridTables();

        // 「全部线路」聚合订阅 token:一条链接包含该车友所有未停用线路的节点
        addColumnIfMissing("user", "all_sub_token",
                "ALTER TABLE `user` ADD COLUMN `all_sub_token` VARCHAR(64) NULL COMMENT '全部线路聚合订阅token'");

        // 分配给车友的转发需要保存一条可直接导入客户端的链接，供聚合订阅输出。
        addColumnIfMissing("forward", "client_link",
                "ALTER TABLE `forward` ADD COLUMN `client_link` VARCHAR(1024) NULL COMMENT '车友转发客户端分享链接'");
    }

    /** 创建协议/中转/线路表。所有语句都可重复执行,单条失败不会阻断面板启动。 */
    private void createHybridTables() {
        executeDdl("inbound", "CREATE TABLE IF NOT EXISTS `inbound` ("
                + "`id` INT(10) NOT NULL AUTO_INCREMENT,"
                + "`node_id` INT(10) NOT NULL,"
                + "`tag` VARCHAR(100) NOT NULL,"
                + "`protocol` VARCHAR(50) NOT NULL,"
                + "`listen_port` INT(10) NOT NULL,"
                + "`security` VARCHAR(20) NOT NULL DEFAULT 'reality',"
                + "`sni` VARCHAR(255) NULL,"
                + "`dest` VARCHAR(255) NULL,"
                + "`public_key` VARCHAR(255) NULL,"
                + "`private_key` VARCHAR(255) NULL,"
                + "`short_id` VARCHAR(100) NULL,"
                + "`config_json` LONGTEXT NULL,"
                + "`remark` VARCHAR(255) NULL,"
                + "`status` INT(10) NOT NULL DEFAULT 1,"
                + "`created_time` BIGINT(20) NOT NULL,"
                + "`updated_time` BIGINT(20) NULL,"
                + "PRIMARY KEY (`id`), KEY `idx_inbound_node` (`node_id`)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

        executeDdl("inbound_user", "CREATE TABLE IF NOT EXISTS `inbound_user` ("
                + "`id` INT(10) NOT NULL AUTO_INCREMENT,"
                + "`inbound_id` INT(10) NOT NULL,"
                + "`user_id` INT(10) NOT NULL,"
                + "`uuid` VARCHAR(100) NULL,"
                + "`password` VARCHAR(255) NULL,"
                + "`gost_forward_id` INT(10) NULL,"
                + "`sub_token` VARCHAR(100) NULL,"
                + "`status` INT(10) NOT NULL DEFAULT 1,"
                + "`created_time` BIGINT(20) NOT NULL,"
                + "PRIMARY KEY (`id`), KEY `idx_iu_inbound` (`inbound_id`), KEY `idx_iu_user` (`user_id`)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

        executeDdl("landing", "CREATE TABLE IF NOT EXISTS `landing` ("
                + "`id` INT(10) NOT NULL AUTO_INCREMENT,"
                + "`name` VARCHAR(100) NOT NULL,"
                + "`type` VARCHAR(30) NOT NULL,"
                + "`link` LONGTEXT NULL,"
                + "`outbound_json` LONGTEXT NULL,"
                + "`remark` VARCHAR(255) NULL,"
                + "`status` INT(10) NOT NULL DEFAULT 1,"
                + "`created_time` BIGINT(20) NOT NULL,"
                + "`updated_time` BIGINT(20) NULL,"
                + "PRIMARY KEY (`id`)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

        executeDdl("inbound_line", "CREATE TABLE IF NOT EXISTS `inbound_line` ("
                + "`id` INT(10) NOT NULL AUTO_INCREMENT,"
                + "`user_id` INT(10) NOT NULL,"
                + "`node_id` INT(10) NOT NULL,"
                + "`landing_id` INT(10) NULL,"
                + "`sub_token` VARCHAR(100) NULL,"
                + "`flow` BIGINT(20) NULL,"
                + "`exp_time` BIGINT(20) NULL,"
                + "`status` INT(10) NOT NULL DEFAULT 1,"
                + "`created_time` BIGINT(20) NOT NULL,"
                + "`updated_time` BIGINT(20) NULL,"
                + "PRIMARY KEY (`id`), KEY `idx_line_user` (`user_id`),"
                + "KEY `idx_line_user_node` (`user_id`, `node_id`)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

        addColumnIfMissing("inbound", "landing_id",
                "ALTER TABLE `inbound` ADD COLUMN `landing_id` INT(10) NULL COMMENT '落地ID:空=直连,有=经该落地中转出网'");

        // 协议限速器不绑定 tunnel。旧库该列是 NOT NULL,改为可空后才可保存协议用户。
        executeDdl("speed_limit.tunnel_id",
                "ALTER TABLE `speed_limit` MODIFY COLUMN `tunnel_id` BIGINT(20) NULL DEFAULT NULL");
    }

    private void executeDdl(String name, String ddl) {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate(ddl);
            log.info("表结构迁移: {} 已完成", name);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            // 表已存在、列已存在等幂等场景不需要反复刷 warning;其它错误保留日志便于排查。
            if (msg.contains("already exists") || msg.contains("Duplicate") || msg.contains("1060")) {
                log.debug("表结构迁移: {} 已存在,跳过", name);
            } else {
                log.warn("表结构迁移失败 {}: {}", name, msg);
            }
        }
    }

    /** 列不存在才执行 ddl;任何异常都吞掉(只记日志),不能因为迁移失败导致面板起不来 */
    private void addColumnIfMissing(String table, String column, String ddl) {
        try (Connection conn = dataSource.getConnection()) {
            if (columnExists(conn, table, column)) {
                return;
            }
            try (Statement st = conn.createStatement()) {
                st.executeUpdate(ddl);
                log.info("表结构迁移: {}.{} 已添加", table, column);
            }
        } catch (Exception e) {
            // 并发启动时另一个实例可能刚好加完(1060 Duplicate column),这属于正常情况
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("Duplicate column") || msg.contains("1060")) {
                log.debug("表结构迁移: {}.{} 已存在,跳过", table, column);
            } else {
                log.warn("表结构迁移失败 {}.{}: {}", table, column, msg);
            }
        }
    }

    private boolean columnExists(Connection conn, String table, String column) throws Exception {
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getColumns(conn.getCatalog(), null, table, column)) {
            return rs.next();
        }
    }
}
