package com.chuhezhe.mapper;

import com.chuhezhe.entity.Order;
import com.chuhezhe.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class MapperQueryTest {

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private LogisticsMapper logisticsMapper;

    @Test
    void countsMatchSeededData() {
        assertEquals(1000L, userMapper.selectCount(null));
        assertEquals(200L, productMapper.selectCount(null));
        assertEquals(30000L, orderMapper.selectCount(null));
        assertEquals(30000L, logisticsMapper.selectCount(null));
    }

    @Test
    void canFetchRowsByMapper() {
        User user = userMapper.selectById(1L);
        assertNotNull(user);
        assertEquals("user_1", user.getUsername());

        List<Order> orders = orderMapper.selectList(null);
        assertTrue(orders.size() >= 30000);
        assertNotNull(orders.get(0).getOrderNo());
    }
}
