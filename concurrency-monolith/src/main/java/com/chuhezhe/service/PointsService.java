package com.chuhezhe.service;

import com.chuhezhe.entity.User;
import com.chuhezhe.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PointsService {

    private static final Logger log = LoggerFactory.getLogger(PointsService.class);

    private final UserMapper userMapper;

    public PointsService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /** 模拟加积分，需要再查一次数据库（非核心逻辑）。 */
    public void add(Long userId, java.math.BigDecimal amount) {
        User user = userMapper.selectById(userId);
        sleep(100);
        long points = amount == null ? 0 : amount.longValue();
        log.info("[POINTS] 用户 {} 下单获得积分 {}", user == null ? userId : user.getUsername(), points);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
