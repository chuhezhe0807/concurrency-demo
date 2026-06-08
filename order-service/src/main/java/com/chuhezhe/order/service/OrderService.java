package com.chuhezhe.order.service;

import com.chuhezhe.order.client.ProductClient;
import com.chuhezhe.order.dto.CreateOrderRequest;
import com.chuhezhe.order.dto.DeductResult;
import com.chuhezhe.order.dto.OrderResult;
import com.chuhezhe.order.dto.ProductDTO;
import com.chuhezhe.order.entity.Order;
import com.chuhezhe.order.mapper.OrderMapper;
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

    private final OrderMapper orderMapper;
    private final ProductClient productClient;

    public OrderService(OrderMapper orderMapper, ProductClient productClient) {
        this.orderMapper = orderMapper;
        this.productClient = productClient;
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

    private String generateOrderNo() {
        return "ORD" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000, 9999);
    }
}
