package com.joinsoft.modules.flow.controller;

import com.joinsoft.common.api.vo.Result;
import com.joinsoft.common.aspect.annotation.AutoLog;
import com.joinsoft.modules.flow.entity.ActRuExecution;
import com.joinsoft.modules.flow.service.IActRuExecutionService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行中流程实例管理接口。
 */
@Api(value = "运行时流程执行实例", tags = "运行流程")
@Slf4j
@RestController
@RequestMapping("/act/execution")
public class ActRuExecutionController {

    private final IActRuExecutionService actRuExecutionService;

    public ActRuExecutionController(IActRuExecutionService actRuExecutionService) {
        this.actRuExecutionService = actRuExecutionService;
    }

    @AutoLog(value = "运行时流程查询")
    @ApiOperation(value = "运行时流程查询", notes = "运行时流程查询")
    @GetMapping("/running")
    public Result<?> queryRunningPageList(String searchBusinesskey,
                                          @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
                                          @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize) {
        return Result.OK(actRuExecutionService.getRunningPageList(searchBusinesskey, pageNo, pageSize));
    }

    @ApiOperation(value = "撤销", notes = "流程发起者可以对流程进行撤销处理")
    @PostMapping("/repeal")
    public Result<?> repeal(@RequestBody ActRuExecution actRuExecution) {
        return actRuExecutionService.repeal(actRuExecution);
    }

    @ApiOperation(value = "终止", notes = "在任意节点终止流程实例")
    @PostMapping("/terminal")
    public Result<?> terminal(@RequestBody ActRuExecution actRuExecution) {
        return actRuExecutionService.terminal(actRuExecution);
    }
}
