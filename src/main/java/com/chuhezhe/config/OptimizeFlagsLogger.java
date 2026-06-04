package com.chuhezhe.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class OptimizeFlagsLogger {

    private static final Logger log = LoggerFactory.getLogger(OptimizeFlagsLogger.class);

    private final OptimizeProperties props;

    public OptimizeFlagsLogger(OptimizeProperties props) {
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logFlags() {
        log.info("优化开关状态 -> batchQuery={}, hikariTuning={}, cache={}, async={}, mq={}",
                props.isBatchQuery(), props.isHikariTuning(), props.isCache(),
                props.isAsync(), props.isMq());
    }
}
