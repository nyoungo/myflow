package com.joinsoft.modules.flow.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.joinsoft.common.api.vo.Result;
import com.joinsoft.modules.flow.entity.ActRuExecution;

/**
 * 运行中流程实例服务。
 */
public interface IActRuExecutionService {

    Page<ActRuExecution> getRunningPageList(String searchBusinesskey, Integer pageNo, Integer pageSize);

    Result<?> repeal(ActRuExecution execution);

    Result<?> terminal(ActRuExecution execution);
}
