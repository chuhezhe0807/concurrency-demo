-- ============================================================
-- 高并发演示项目：建表 + 测试数据
-- 由 docker-compose 在 MySQL 首次初始化时自动执行
-- （挂载到容器 /docker-entrypoint-initdb.d）
--
-- 规模（适中即可，用于连接争用与压测体感；N+1 从代码逻辑即可看出）：
--   user      1,000
--   product     200
--   order    30,000
--   logistics 30,000（每订单一条）
-- ============================================================

CREATE DATABASE IF NOT EXISTS concurrency_demo
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE concurrency_demo;

-- ---------- 表结构 ----------
DROP TABLE IF EXISTS t_logistics;
DROP TABLE IF EXISTS t_order;
DROP TABLE IF EXISTS t_product;
DROP TABLE IF EXISTS t_user;

CREATE TABLE t_user (
  id          BIGINT       NOT NULL PRIMARY KEY,
  username    VARCHAR(64)  NOT NULL,
  phone       VARCHAR(20)  NOT NULL,
  email       VARCHAR(128) NOT NULL,
  deleted     TINYINT      NOT NULL DEFAULT 0,
  create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_product (
  id          BIGINT        NOT NULL PRIMARY KEY,
  name        VARCHAR(128)  NOT NULL,
  price       DECIMAL(10,2) NOT NULL,
  stock       INT           NOT NULL,
  deleted     TINYINT       NOT NULL DEFAULT 0,
  update_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_order (
  id          BIGINT        NOT NULL PRIMARY KEY,
  order_no    VARCHAR(32)   NOT NULL,
  user_id     BIGINT        NOT NULL,
  product_id  BIGINT        NOT NULL,
  quantity    INT           NOT NULL,
  amount      DECIMAL(12,2) NOT NULL,
  status      TINYINT       NOT NULL DEFAULT 0,
  create_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted     TINYINT       NOT NULL DEFAULT 0,
  KEY idx_order_user (user_id),
  KEY idx_order_product (product_id),
  UNIQUE KEY uk_order_no (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_logistics (
  id          BIGINT       NOT NULL PRIMARY KEY,
  order_id    BIGINT       NOT NULL,
  carrier     VARCHAR(32)  NOT NULL,
  tracking_no VARCHAR(40)  NOT NULL,
  status      TINYINT      NOT NULL DEFAULT 0,
  deleted     TINYINT      NOT NULL DEFAULT 0,
  KEY idx_logistics_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------- Seata AT 模式回滚日志表（US-021） ----------
-- AT 模式一阶段执行业务 SQL 时，把数据「前镜像/后镜像」写入 undo_log 并随本地事务一起提交；
-- 二阶段全局回滚时据此还原，全局提交时异步删除。order/product 两服务共用同一物理库 concurrency_demo，
-- 故一张 undo_log 即可。DDL 取自 Seata 2.x 官方 MySQL 脚本。
DROP TABLE IF EXISTS undo_log;
CREATE TABLE undo_log (
  branch_id     BIGINT       NOT NULL COMMENT 'branch transaction id',
  xid           VARCHAR(128) NOT NULL COMMENT 'global transaction id',
  context       VARCHAR(128) NOT NULL COMMENT 'undo_log context, such as serialization',
  rollback_info LONGBLOB     NOT NULL COMMENT 'rollback info',
  log_status    INT          NOT NULL COMMENT '0:normal status,1:defense status',
  log_created   DATETIME(6)  NOT NULL COMMENT 'create datetime',
  log_modified  DATETIME(6)  NOT NULL COMMENT 'modify datetime',
  UNIQUE KEY ux_undo_log (xid, branch_id)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COMMENT='AT transaction mode undo table';

-- ---------- 可靠消息最终一致（US-022） ----------
-- 本地消息表（transactional outbox）：order-service 建订单时，把「待发的扣库存消息」与订单写进同一个本地事务，
-- 消除「DB 写成功但消息没发出」的双写缺口。轮询器再把 NEW 消息可靠投递到 RabbitMQ，confirm 后置 SENT。
DROP TABLE IF EXISTS t_order_outbox;
CREATE TABLE t_order_outbox (
  id          BIGINT       NOT NULL PRIMARY KEY,
  order_no    VARCHAR(32)  NOT NULL,
  payload     TEXT         NOT NULL COMMENT '扣库存消息 JSON',
  status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=NEW 待发送, 1=SENT 已确认投递',
  retry_count INT          NOT NULL DEFAULT 0,
  create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted     TINYINT      NOT NULL DEFAULT 0,
  UNIQUE KEY uk_outbox_order_no (order_no),
  KEY idx_outbox_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地消息表';

-- product-service 幂等去重表：消费扣库存消息时，先以 order_no 占位（主键冲突=已处理过），保证重复投递只扣一次库存。
DROP TABLE IF EXISTS t_stock_deduct_log;
CREATE TABLE t_stock_deduct_log (
  order_no    VARCHAR(32)  NOT NULL PRIMARY KEY,
  product_id  BIGINT       NOT NULL,
  quantity    INT          NOT NULL,
  create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='扣库存幂等去重表';

-- ---------- 数字生成器（0-9 数字表，交叉连接得到序列） ----------
-- 用普通表而非 TEMPORARY 表：MySQL 不允许在同一查询中多次引用同一 TEMPORARY 表
DROP TABLE IF EXISTS digits;
CREATE TABLE digits (d INT);
INSERT INTO digits VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9);

-- ---------- 测试数据 ----------
-- 用户 1..1000
INSERT INTO t_user (id, username, phone, email)
SELECT n,
       CONCAT('user_', n),
       CONCAT('138', LPAD(n, 8, '0')),
       CONCAT('user', n, '@demo.com')
FROM (SELECT a.d + b.d*10 + c.d*100 + d.d*1000 AS n
      FROM digits a, digits b, digits c, digits d) seq
WHERE n BETWEEN 1 AND 1000;

-- 商品 1..200（库存设大，避免下单演示时被耗尽）
INSERT INTO t_product (id, name, price, stock)
SELECT n,
       CONCAT('product_', n),
       ROUND(10 + (n % 500) + 0.99, 2),
       1000000
FROM (SELECT a.d + b.d*10 + c.d*100 + d.d*1000 AS n
      FROM digits a, digits b, digits c, digits d) seq
WHERE n BETWEEN 1 AND 200;

-- 订单 1..30000
INSERT INTO t_order (id, order_no, user_id, product_id, quantity, amount, status, create_time)
SELECT n,
       CONCAT('ORD', LPAD(n, 10, '0')),
       1 + (n % 1000),
       1 + (n % 200),
       1 + (n % 5),
       ROUND((10 + ((1 + (n % 200)) % 500) + 0.99) * (1 + (n % 5)), 2),
       (n % 4),
       NOW() - INTERVAL (n % 365) DAY
FROM (SELECT a.d + b.d*10 + c.d*100 + d.d*1000 + e.d*10000 AS n
      FROM digits a, digits b, digits c, digits d, digits e) seq
WHERE n BETWEEN 1 AND 30000;

-- 物流：每订单一条
INSERT INTO t_logistics (id, order_id, carrier, tracking_no, status)
SELECT n,
       n,
       ELT(1 + (n % 3), 'SF', 'YTO', 'ZTO'),
       CONCAT(ELT(1 + (n % 3), 'SF', 'YTO', 'ZTO'), LPAD(n, 12, '0')),
       (n % 3)
FROM (SELECT a.d + b.d*10 + c.d*100 + d.d*1000 + e.d*10000 AS n
      FROM digits a, digits b, digits c, digits d, digits e) seq
WHERE n BETWEEN 1 AND 30000;

DROP TABLE IF EXISTS digits;

-- US-013 方案二：binlog 订阅删缓存需要 demo 用户有复制权限（以伪从库身份读 binlog）。
-- MySQL 8 默认已开启 binlog 且为 ROW 格式，无需额外配置。
GRANT REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'demo'@'%';
FLUSH PRIVILEGES;
