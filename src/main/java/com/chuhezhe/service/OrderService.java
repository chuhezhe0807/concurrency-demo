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
import com.chuhezhe.mapper.LogisticsMapper;
import com.chuhezhe.mapper.OrderMapper;
import com.chuhezhe.mapper.ProductMapper;
import com.chuhezhe.mapper.UserMapper;
import com.chuhezhe.vo.OrderVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class OrderService {

    private final OrderMapper orderMapper;
    private final UserMapper userMapper;
    private final ProductMapper productMapper;
    private final LogisticsMapper logisticsMapper;
    private final SmsService smsService;
    private final EmailService emailService;
    private final PointsService pointsService;

    public OrderService(OrderMapper orderMapper,
                        UserMapper userMapper,
                        ProductMapper productMapper,
                        LogisticsMapper logisticsMapper,
                        SmsService smsService,
                        EmailService emailService,
                        PointsService pointsService) {
        this.orderMapper = orderMapper;
        this.userMapper = userMapper;
        this.productMapper = productMapper;
        this.logisticsMapper = logisticsMapper;
        this.smsService = smsService;
        this.emailService = emailService;
        this.pointsService = pointsService;
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

        // 非核心：短信 + 邮件 + 积分，全部同步执行（拖慢响应）
        smsService.send(req.getUserId(), order.getOrderNo());
        emailService.send(req.getUserId(), order.getOrderNo());
        pointsService.add(req.getUserId(), amount);

        return new OrderResult(order.getId(), order.getOrderNo(), System.currentTimeMillis() - start);
    }

    private String generateOrderNo() {
        return "ORD" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000, 9999);
    }

    public List<OrderVO> listOrders(long pageNo, long pageSize) {
        IPage<Order> page = orderMapper.selectPage(new Page<>(pageNo, pageSize), null);
        return fillOrderDetails(page.getRecords());
    }

    /**
     * N+1 写法：一页订单查出后，循环对每条订单分别查用户/商品/物流。
     * 20 条订单 = 1(列表) + 20*3(明细) = 61 次数据库查询。
     */
    private List<OrderVO> fillOrderDetails(List<Order> orders) {
        List<OrderVO> result = new ArrayList<>(orders.size());
        for (Order order : orders) {
            User user = userMapper.selectById(order.getUserId());
            Product product = productMapper.selectById(order.getProductId());
            Logistics logistics = logisticsMapper.selectOne(
                    new LambdaQueryWrapper<Logistics>().eq(Logistics::getOrderId, order.getId()));

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
            result.add(vo);
        }
        return result;
    }
}
