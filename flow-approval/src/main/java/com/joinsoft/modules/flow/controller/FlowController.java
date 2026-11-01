package com.joinsoft.modules.flow.controller;

import com.joinsoft.common.api.vo.Result;
import com.joinsoft.modules.flow.dto.ApprovalData;
import com.joinsoft.modules.flow.dto.StartData;
import com.joinsoft.modules.flow.service.IFlowService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * 工作流操作接口，路径、方法与参数名与原项目保持一致。
 */
@Api(value = "工作流", tags = "工作流")
@Slf4j
@RestController
@RequestMapping("/flow")
public class FlowController {

    @Resource
    private IFlowService flowService;

    @ApiOperation(value = "启动", notes = "启动流程实例")
    @PostMapping("/start")
    public Result<?> start(@RequestBody StartData startData) {
        return flowService.start(startData);
    }

    @ApiOperation(value = "审批通过", notes = "审批待办任务")
    @PostMapping("/pass")
    public Result<?> pass(@RequestBody ApprovalData approvalData) {
        return flowService.pass(approvalData);
    }

    @ApiOperation(value = "转办", notes = "A转给B审批，B审批后进入下一节点")
    @PostMapping("/turnTo")
    public Result<?> turnTo(@RequestBody ApprovalData approvalData) {
        return flowService.turnTo(approvalData);
    }

    @ApiOperation(value = "委派", notes = "A转给B审批，B审批后回到A审批")
    @PostMapping("/appoint")
    public Result<?> appoint(@RequestBody ApprovalData approvalData) {
        return flowService.appoint(approvalData);
    }

    @ApiOperation(value = "跳转", notes = "把当前实例跳转到任意办理节点")
    @PostMapping("/jump")
    public Result<?> jump(@RequestBody ApprovalData approvalData) {
        return flowService.jump(approvalData);
    }

    @ApiOperation(value = "已结束流程退回最后节点", notes = "已结束流程退回最后节点")
    @PostMapping("/backToLastNode")
    public Result<?> backToLastNode(@RequestBody ApprovalData approvalData) {
        return flowService.backToLastNode(approvalData);
    }

    @ApiOperation(value = "取回", notes = "下一节点未处理前允许上一节点提交人取回")
    @PostMapping("/getBack")
    public Result<?> getBack(@RequestBody ApprovalData approvalData) {
        return flowService.getBack(approvalData);
    }

    @ApiOperation(value = "撤销", notes = "流程发起者撤销流程")
    @PostMapping("/repeal")
    public Result<?> repeal(@RequestBody ApprovalData approvalData) {
        return flowService.repeal(approvalData);
    }

    @ApiOperation(value = "认领", notes = "无办理人的待办可被认领")
    @PostMapping("/claim")
    public Result<?> claim(@RequestBody ApprovalData approvalData) {
        return flowService.claim(approvalData);
    }

    @ApiOperation(value = "催办", notes = "通知当前活动任务处理人")
    @PostMapping("/urge")
    public Result<?> urge(@RequestBody ApprovalData approvalData) {
        return flowService.urge(approvalData);
    }

    @ApiOperation(value = "终止", notes = "在任意节点终止流程实例")
    @PostMapping("/terminal")
    public Result<?> terminal(@RequestBody ApprovalData approvalData) {
        return flowService.terminal(approvalData);
    }

    @ApiOperation(value = "预测工作流路径", notes = "预测工作流路径")
    @GetMapping("/forecast")
    public Result<?> forecast(@RequestParam String procInstId) {
        return flowService.forecast(procInstId);
    }
}
