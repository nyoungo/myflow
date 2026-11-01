package com.joinsoft.modules.flow.service;

import com.joinsoft.common.api.vo.Result;

/**
 * 流程模板 / 历史查询服务，方法签名与原项目保持一致。
 */
public interface IModelService {

    Result<?> groupList();

    Result<?> getDefinitionByDefId(String procDefId);

    Result<?> getUsersByDefId(String procDefId, String taskDefId);

    Result<?> getPic(String businessKey);

    Result<?> getHis(String businessKey);

    Result<?> getFlowInfo(String businessKey);
}
