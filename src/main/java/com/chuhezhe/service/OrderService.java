package com.chuhezhe.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.chuhezhe.dto.CreateOrderRequest;
import com.chuhezhe.dto.OrderResult;
import com.chuhezhe.entity.Logistics;
import com.chuhezhe.entity.Order;
import com.chuhezhe.entity.Product;
import com.chuhezhe.entity.User;
import com.chuhezhe.config.OptimizeProperties;
import com.chuhezhe.event.OrderPlacedEvent;
import com.chuhezhe.mapper.LogisticsMapper;
import com.chuhezhe.mapper.OrderMapper;
import com.chuhezhe.mapper.ProductMapper;
import com.chuhezhe.mapper.UserMapper;
import com.chuhezhe.vo.OrderVO;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class OrderService {

    private final OrderMapper orderMapper;
    private final UserMapper userMapper;
    private final ProductMapper productMapper;
    private final LogisticsMapper logisticsMapper;
    private final SmsService smsService;
    private final EmailService emailService;
    private final PointsService pointsService;
    private final ProductService productService;
    private final OptimizeProperties optimizeProperties;
    private final ApplicationEventPublisher eventPublisher;

    public OrderService(OrderMapper orderMapper,
                        UserMapper userMapper,
                        ProductMapper productMapper,
                        LogisticsMapper logisticsMapper,
                        SmsService smsService,
                        EmailService emailService,
                        PointsService pointsService,
                        ProductService productService,
                        OptimizeProperties optimizeProperties,
                        ApplicationEventPublisher eventPublisher) {
        this.orderMapper = orderMapper;
        this.userMapper = userMapper;
        this.productMapper = productMapper;
        this.logisticsMapper = logisticsMapper;
        this.smsService = smsService;
        this.emailService = emailService;
        this.pointsService = pointsService;
        this.productService = productService;
        this.optimizeProperties = optimizeProperties;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 下单：核心(扣库存+创建订单)与非核心(短信/邮件/积分)全部同步执行。
     * 用户要等到所有非核心逻辑都跑完才拿到响应——这正是后续 US-014 异步化要解决的问题。
     */
    @Transactional
    public OrderResult placeOrder(CreateOrderRequest req) {
        long start = System.currentTimeMillis();
        int quantity = req.getQuantity() == null ? 1 : req.getQuantity();
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity 必须大于 0");
        }

        // 核心 1：扣库存（带库存充足判断，乐观更新）
        int updated = productMapper.update(null,
                new LambdaUpdateWrapper<Product>()
                        .setSql("stock = stock - " + quantity)
                        .eq(Product::getId, req.getProductId())
                        .ge(Product::getStock, quantity));
        if (updated == 0) {
            throw new IllegalStateException("库存不足或商品不存在: productId=" + req.getProductId());
        }

        // 核心 2：创建订单
        Product product = productMapper.selectById(req.getProductId());
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

        // 非核心：短信 + 邮件 + 积分。
        // async=true 时发布 OrderPlacedEvent，由 @TransactionalEventListener(AFTER_COMMIT) 在事务
        // 提交后才丢入 orderExecutor 线程池执行——核心若回滚则事件不投递，不会误发通知；
        // false 时全部同步执行（US-006 行为，拖慢响应）。
        if (optimizeProperties.isAsync()) {
            eventPublisher.publishEvent(new OrderPlacedEvent(req.getUserId(), order.getOrderNo(), amount));
        } else {
            smsService.send(req.getUserId(), order.getOrderNo());
            emailService.send(req.getUserId(), order.getOrderNo());
            pointsService.add(req.getUserId(), amount);
        }

        return new OrderResult(order.getId(), order.getOrderNo(), System.currentTimeMillis() - start);
    }

    private String generateOrderNo() {
        return "ORD" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000, 9999);
    }

    public List<OrderVO> listOrders(long pageNo, long pageSize) {
        IPage<Order> page = orderMapper.selectPage(new Page<>(pageNo, pageSize), null);
        List<Order> orders = page.getRecords();
        // 开关分流：开启批量查询走 IN + 内存匹配（约 4 次查询），否则保持 N+1（61 次）。
        return optimizeProperties.isBatchQuery()
                ? fillOrderDetailsBatch(orders)
                : fillOrderDetails(orders);
    }

    /**
     * N+1 写法：一页订单查出后，循环对每条订单分别查用户/商品/物流。
     * 20 条订单 = 1(列表) + 20*3(明细) = 61 次数据库查询。
     */
    private List<OrderVO> fillOrderDetails(List<Order> orders) {
        List<OrderVO> result = new ArrayList<>(orders.size());
        for (Order order : orders) {
            User user = userMapper.selectById(order.getUserId());
            // 商品查询走 ProductService，cache 开关开启时命中 Redis（US-012）
            Product product = productService.getById(order.getProductId());
            Logistics logistics = logisticsMapper.selectOne(
                    new LambdaQueryWrapper<Logistics>().eq(Logistics::getOrderId, order.getId()));
            result.add(buildOrderVO(order, user, product, logistics));
        }
        return result;
    }

    /**
     * 批量写法（US-010）：先收集本页所有 userId/productId/orderId，各发一次 IN 查询，
     * 再在内存里用 Map 匹配。无论一页多少条订单，固定 1(列表)+3(批量) = 约 4 次查询。
     */
    private List<OrderVO> fillOrderDetailsBatch(List<Order> orders) {
        if (orders.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> userIds = orders.stream().map(Order::getUserId).distinct().toList();
        List<Long> productIds = orders.stream().map(Order::getProductId).distinct().toList();
        List<Long> orderIds = orders.stream().map(Order::getId).toList();

        Map<Long, User> userMap = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        Map<Long, Product> productMap = productMapper.selectBatchIds(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<Long, Logistics> logisticsMap = logisticsMapper.selectList(
                        new LambdaQueryWrapper<Logistics>().in(Logistics::getOrderId, orderIds)).stream()
                .collect(Collectors.toMap(Logistics::getOrderId, Function.identity(), (a, b) -> a));

        List<OrderVO> result = new ArrayList<>(orders.size());
        for (Order order : orders) {
            result.add(buildOrderVO(order,
                    userMap.get(order.getUserId()),
                    productMap.get(order.getProductId()),
                    logisticsMap.get(order.getId())));
        }
        return result;
    }

    private OrderVO buildOrderVO(Order order, User user, Product product, Logistics logistics) {
        OrderVO vo = new OrderVO();
        vo.setOrderId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setQuantity(order.getQuantity());
        vo.setAmount(order.getAmount());
        vo.setStatus(order.getStatus());
        vo.setCreateTime(order.getCreateTime());
        if (user != null) {
            vo.setUserId(user.getId());
            vo.setUserName(user.getUsername());
            vo.setUserPhone(user.getPhone());
        }
        if (product != null) {
            vo.setProductId(product.getId());
            vo.setProductName(product.getName());
            vo.setProductPrice(product.getPrice());
        }
        if (logistics != null) {
            vo.setCarrier(logistics.getCarrier());
            vo.setTrackingNo(logistics.getTrackingNo());
        }
        return vo;
    }
}
