package com.hmdp.service.impl;

import com.hmdp.service.IBlogService;
import jakarta.annotation.Resource;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class FeedRebuildConsumer {

    @Resource
    private IBlogService blogService;

    @RabbitListener(queues = "feed.rebuild.queue")
    public void handleRebuild(Long userId) {
        blogService.rebuildFeed(userId);
    }
}