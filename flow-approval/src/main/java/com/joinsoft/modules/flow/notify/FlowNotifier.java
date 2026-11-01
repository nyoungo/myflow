package com.joinsoft.modules.flow.notify;

/**
 * 通知 SPI：默认实现只写日志，可通过提供自己的 {@code FlowNotifier} Bean 替换为
 * 邮件 / 短信 / WebSocket / 站内信等实现。
 */
public interface FlowNotifier {

    void notify(FlowMessage message);
}
