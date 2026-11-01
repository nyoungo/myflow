package com.joinsoft.modules.flow.engine;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 流程令牌：表示一个正在执行的流程位置。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FlowToken {

    private String id;
    private String node;
}
