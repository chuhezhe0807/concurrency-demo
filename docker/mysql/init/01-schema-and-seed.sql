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
