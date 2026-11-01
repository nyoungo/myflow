package com.joinsoft.modules.flow.service;

import com.joinsoft.common.api.vo.Result;
import com.joinsoft.modules.flow.dto.ApprovalData;
import com.joinsoft.modules.flow.dto.StartData;

/**
 * 流程操作服务，方法签名与原项目保持一致。
 */
public interface IFlowService {

    Result<?> start(StartData startData);

    Result<?> pass(ApprovalData approvalData);

    Result<?> turnTo(ApprovalData approvalData);

    Result<?> appoint(ApprovalData approvalData);

    Result<?> jump(ApprovalData approvalData);

    Result<?> getBack(ApprovalData approvalData);

    Result<?> repeal(ApprovalData approvalData);

    Result<?> claim(ApprovalData approvalData);

    Result<?> urge(ApprovalData approvalData);

    Result<?> terminal(ApprovalData approvalData);

    Result<?> backToLastNode(ApprovalData approvalData);

    Result<?> forecast(String procInstId);
}
