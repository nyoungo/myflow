package com.joinsoft.modules.flow.controller;

import com.joinsoft.common.api.vo.Result;
import com.joinsoft.modules.flow.service.IModelService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * 工作流模板查询接口。
 */
@Api(value = "工作流模板", tags = "工作流模板")
@Slf4j
@RestController
@RequestMapping("/flow/model")
public class ModelController {

    @Resource
    private IModelService modelService;

    @ApiOperation(value = "获取所有已发布的流程信息", notes = "按分类分组")
    @GetMapping("/groupList")
    public Result<?> groupList() {
        return modelService.groupList();
    }

    @ApiOperation(value = "通过流程定义id获取流程定义信息", notes = "返回用户任务节点列表")
    @GetMapping("/getDefinitionByDefId")
    public Result<?> getDefinitionByDefId(@RequestParam String procDefId) {
        return modelService.getDefinitionByDefId(procDefId);
    }

    @ApiOperation(value = "通过流程节点定义id获取节点审核人", notes = "通过流程节点定义id获取节点审核人")
    @GetMapping("/getUsersByDefId")
    public Result<?> getUsersByDefId(@RequestParam String procDefId, @RequestParam String taskDefId) {
        return modelService.getUsersByDefId(procDefId, taskDefId);
    }

    @ApiOperation(value = "根据businessKey获取流程图", notes = "返回 Base64 SVG")
    @GetMapping("/getPic")
    public Result<?> getPic(@RequestParam String businessKey) {
        return modelService.getPic(businessKey);
    }

    @ApiOperation(value = "根据businessKey获取审批历史", notes = "根据businessKey获取审批历史")
    @GetMapping("/getHis")
    public Result<?> getHis(@RequestParam String businessKey) {
        return modelService.getHis(businessKey);
    }

    @ApiOperation(value = "根据businessKey获取流程定义", notes = "返回 XML、历史和活动轨迹")
    @GetMapping("/getFlowInfo")
    public Result<?> getFlowInfo(@RequestParam String businessKey) {
        return modelService.getFlowInfo(businessKey);
    }
}
