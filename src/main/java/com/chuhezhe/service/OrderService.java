package com.chuhezhe.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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

import java.util.ArrayList;
import java.util.List;

@Service
public class OrderService {

    private final OrderMapper orderMapper;
    private final UserMapper userMapper;
    private final ProductMapper productMapper;
    private final LogisticsMapper logisticsMapper;

    public OrderService(OrderMapper orderMapper,
                        UserMapper userMapper,
                        ProductMapper productMapper,
                        LogisticsMapper logisticsMapper) {
        this.orderMapper = orderMapper;
        this.userMapper = userMapper;
        this.productMapper = productMapper;
        this.logisticsMapper = logisticsMapper;
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
