package com.joinsoft.modules.flow.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.joinsoft.common.api.vo.Result;
import com.joinsoft.modules.flow.entity.ActBusinessData;

import java.util.List;
import java.util.Map;

/**
 * 工作流实例 / 业务主表服务，保留原项目方法签名。
 */
public interface IActBusinessDataService extends IService<ActBusinessData> {

    ActBusinessData getByBusinessId(String businessId);

    ActBusinessData getByProcInstId(String procInstId);

    List<ActBusinessData> getByBusinessIds(List<String> idList);

    Result<?> queryTodoList(Page<Map<String, Object>> page, ActBusinessData actBusinessData);

    void updateCommentFlowState(String id, String s);

    Result<?> queryDoneList(Page<Map<String, Object>> page, ActBusinessData actBusinessData);

    Result<?> queryAllList(Page<Map<String, Object>> page, ActBusinessData actBusinessData);

    Result<?> queryMyApplyList(Page<Map<String, Object>> page, ActBusinessData actBusinessData);

    Result<?> queryBackList(Page<Map<String, Object>> page, ActBusinessData actBusinessData);
}
