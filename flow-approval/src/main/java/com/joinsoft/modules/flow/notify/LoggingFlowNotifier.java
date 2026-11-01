package com.joinsoft.modules.flow.notify;

import lombok.extern.slf4j.Slf4j;

/**
 * 默认通知实现：日志输出。
 */
@Slf4j
public class LoggingFlowNotifier implements FlowNotifier {

    @Override
    public void notify(FlowMessage message) {
        if (message == null) {
            return;
        }
        log.info("[流程通知][{}] 接收人={} 标题={} 内容={}", message.getType(),
                message.getUserIds(), message.getTitle(), message.getContent());
    }
}
