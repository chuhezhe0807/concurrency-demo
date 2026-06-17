package com.chuhezhe.order.service;

import com.chuhezhe.order.client.ProductClient;
import com.chuhezhe.order.dto.CreateOrderRequest;
import com.chuhezhe.order.dto.DeductResult;
import com.chuhezhe.order.dto.OrderResult;
import com.chuhezhe.order.dto.ProductDTO;
import com.chuhezhe.common.dto.StockDeductMsg;
import com.chuhezhe.order.entity.Order;
import com.chuhezhe.order.entity.OrderOutbox;
import com.chuhezhe.order.mapper.OrderMapper;
import com.chuhezhe.order.mapper.OrderOutboxMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.seata.spring.annotation.GlobalTransactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 跨服务下单（US-019）。
 * <p>
 * 与单体最大的区别：扣库存不再是本地一条 SQL，而是跨进程调用 product-service。这引入了
 * 单体没有的问题——见下方注释与 docs/MICROSERVICES.md：
 * <ul>
 *   <li>网络调用：可能超时/失败，比本地方法慢且不可靠；</li>
 *   <li>数据一致性：扣库存(product-service 的库)与建订单(order-service 的库)是两个独立事务。</li>
 * </ul>
 * <p>
 * US-021（本次）用 Seata AT 模式补上一致性缺口：placeOrder 上加 {@link GlobalTransactional} 开启全局事务，
 * XID 经 Feign 透传给 product-service，扣库存与建订单成为同一全局事务下的两个分支，由 TC 统一提交或回滚。
 * 这样「扣库存成功但建单失败」时，product 已扣的库存会经 undo_log 自动还原，不再出现「扣空却没订单」。
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    /** US-022 订单状态：可靠消息路径用。0=待确认(已下单、扣库存消息待发/在途)，1=已确认(扣库存成功)，2=已取消(库存不足，补偿)。 */
    public static final int STATUS_PENDING = 0;
    public static final int STATUS_CONFIRMED = 1;
    public static final int STATUS_CANCELLED = 2;

    private final OrderMapper orderMapper;
    private final ProductClient productClient;
    private final OrderOutboxMapper outboxMapper;
    private final ObjectMapper objectMapper;

    public OrderService(OrderMapper orderMapper, ProductClient productClient,
                        OrderOutboxMapper outboxMapper, ObjectMapper objectMapper) {
        this.orderMapper = orderMapper;
        this.productClient = productClient;
        this.outboxMapper = outboxMapper;
        this.objectMapper = objectMapper;
    }

    @GlobalTransactional(rollbackFor = Exception.class, name = "place-order-tx")
    @Transactional
    public OrderResult placeOrder(CreateOrderRequest req) {
        long start = System.currentTimeMillis();
        int quantity = req.getQuantity() == null ? 1 : req.getQuantity();
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity 必须大于 0");
        }

        // 1) 跨服务取商品信息（拿单价算金额）
        ProductDTO product = productClient.getById(req.getProductId());
        if (product == null || product.getPrice() == null) {
            throw new IllegalStateException("商品不存在: productId=" + req.getProductId());
        }

        // 2) 跨服务扣库存（核心）。失败则中止下单。
        DeductResult deduct = productClient.deductStock(req.getProductId(), quantity);
        if (deduct == null || !Boolean.TRUE.equals(deduct.getSuccess())) {
            throw new IllegalStateException("库存不足或商品不存在: productId=" + req.getProductId());
        }

        // 3) 建订单（本地库）。已在 @GlobalTransactional 内：若后续抛异常，TC 会回滚本地 insert
        //    并通知 product-service 分支经 undo_log 还原已扣的库存。
        BigDecimal amount = product.getPrice().multiply(BigDecimal.valueOf(quantity));
        Order order = new Order();
        order.setOrderNo(generateOrderNo());
        order.setUserId(req.getUserId());
        order.setProductId(req.getProductId());
        order.setQuantity(quantity);
        order.setAmount(amount);
        order.setStatus(0);
        order.setCreateTime(LocalDateTime.now());
        orderMapper.insert(order);

        // 4) US-021 失败注入：扣库存与建单都成功后故意失败，触发 Seata 全局回滚以演示强一致。
        if (Boolean.TRUE.equals(req.getMockFail())) {
            throw new IllegalStateException("mock 建单后失败，触发 Seata 全局回滚（库存应被还原、订单不落库）");
        }

        log.info("[ORDER] 跨服务下单成功 orderNo={} productId={} qty={} amount={}",
                order.getOrderNo(), req.getProductId(), quantity, amount);
        return new OrderResult(order.getId(), order.getOrderNo(), System.currentTimeMillis() - start);
    }

    /**
     * US-022 可靠消息最终一致下单。与 US-021 的 Seata 强一致是两条独立路径：
     * <p>
     * 这里不再同步 Feign 扣库存，而是「本地事务 + 本地消息表（transactional outbox）」：
     * 在同一个本地事务里把订单(status=待确认)与一条「扣库存消息」写进 t_order 和 t_order_outbox。
     * 二者要么一起提交、要么一起回滚，消除了「订单写成功但消息没发出」/「消息发了但订单没写」的双写缺口。
     * <p>
     * 真正的投递交给 {@link OutboxRelay} 轮询：把 NEW 消息发到 RabbitMQ，收到 broker confirm 后置 SENT。
     * product-service 异步消费扣库存并回传结果，{@link OrderResultListener} 据此把订单确认或补偿取消。
     * <p>
     * 接口立即返回（订单处于「待确认」），库存最终一致——这是与 Seata「调用返回即强一致」的核心取舍。
     */
    @Transactional
    public OrderResult placeOrderReliable(CreateOrderRequest req) {
        long start = System.currentTimeMillis();
        int quantity = req.getQuantity() == null ? 1 : req.getQuantity();
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity 必须大于 0");
        }

        // 1) 取商品单价算金额（只读查询，可同步 Feign；扣库存才走异步消息）
        ProductDTO product = productClient.getById(req.getProductId());
        if (product == null || product.getPrice() == null) {
            throw new IllegalStateException("商品不存在: productId=" + req.getProductId());
        }
        BigDecimal amount = product.getPrice().multiply(BigDecimal.valueOf(quantity));

        // 2) 建订单：状态=待确认。库存尚未扣，等消费端回传结果再确认/取消。
        Order order = new Order();
        order.setOrderNo(generateOrderNo());
        order.setUserId(req.getUserId());
        order.setProductId(req.getProductId());
        order.setQuantity(quantity);
        order.setAmount(amount);
        order.setStatus(STATUS_PENDING);
        order.setCreateTime(LocalDateTime.now());
        orderMapper.insert(order);

        // 3) 同一本地事务里写本地消息表（待发送）。与订单共生死，杜绝双写缺口。
        StockDeductMsg msg = new StockDeductMsg(order.getOrderNo(), req.getUserId(),
                req.getProductId(), quantity, amount);
        OrderOutbox outbox = new OrderOutbox();
        outbox.setOrderNo(order.getOrderNo());
        outbox.setPayload(toJson(msg));
        outbox.setStatus(OrderOutbox.STATUS_NEW);
        outbox.setRetryCount(0);
        outboxMapper.insert(outbox);

        log.info("[ORDER-REL] 可靠消息下单受理 orderNo={} productId={} qty={} amount={} 状态=待确认",
                order.getOrderNo(), req.getProductId(), quantity, amount);
        return new OrderResult(order.getId(), order.getOrderNo(), System.currentTimeMillis() - start);
    }

    /** 消费端回传扣库存结果后，确认或补偿取消订单。幂等：仅当订单仍处于「待确认」时才推进状态。 */
    @Transactional
    public void applyDeductResult(String orderNo, boolean success, String reason) {
        Order order = orderMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, orderNo));
        if (order == null) {
            log.warn("[ORDER-REL] 收到结果但订单不存在 orderNo={}", orderNo);
            return;
        }
        if (order.getStatus() != STATUS_PENDING) {
            log.info("[ORDER-REL] 结果重复投递，订单已是终态，忽略 orderNo={} status={}", orderNo, order.getStatus());
            return;
        }
        int target = success ? STATUS_CONFIRMED : STATUS_CANCELLED;
        order.setStatus(target);
        orderMapper.updateById(order);
        if (success) {
            log.info("[ORDER-REL] 扣库存成功，订单已确认 orderNo={}", orderNo);
        } else {
            // 补偿路径：库存不足，订单取消。库存本就没扣，无需反向冲正；若已扣需发反向消息冲正。
            log.warn("[ORDER-REL] 扣库存失败，订单补偿取消 orderNo={} reason={}", orderNo, reason);
        }
    }

    /** 按订单号查订单（供演示观察异步确认后的状态变化）。 */
    public Order getByOrderNo(String orderNo) {
        return orderMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, orderNo));
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化消息失败", e);
        }
    }

    private String generateOrderNo() {
        return "ORD" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000, 9999);
    }
}
