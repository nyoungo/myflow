package com.joinsoft.modules.flow.controller;

import com.joinsoft.common.api.vo.Result;
import com.joinsoft.common.aspect.annotation.AutoLog;
import com.joinsoft.common.exception.JoinsoftException;
import com.joinsoft.modules.flow.entity.ActReModelEntity;
import com.joinsoft.modules.flow.entity.ActReProcdefEntity;
import com.joinsoft.modules.flow.service.IActReProcdefService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;

/**
 * 流程定义管理接口，路径与原项目 {@code ActReProcdefController} 保持一致。
 */
@Api(value = "流程定义控制器", tags = "流程定义")
@Slf4j
@RestController
@RequestMapping("/act/procdef")
public class ActReProcdefController {

    private final IActReProcdefService actReProcdefService;

    public ActReProcdefController(IActReProcdefService actReProcdefService) {
        this.actReProcdefService = actReProcdefService;
    }

    @AutoLog(value = "分页列表查询")
    @ApiOperation(value = "分页列表查询", notes = "分页列表查询")
    @GetMapping("/list")
    public Result<?> queryPageList(ActReModelEntity actReModel,
                                   @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
                                   @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize) {
        return Result.OK(actReProcdefService.queryPageList(actReModel, pageNo, pageSize));
    }

    @AutoLog(value = "查询所有流程的最新版")
    @ApiOperation(value = "查询所有流程的最新版", notes = "查询所有流程的最新版")
    @GetMapping("/queryLastVersionList")
    public List<ActReProcdefEntity> queryLastVersionList() {
        return actReProcdefService.queryLastVersionList();
    }

    @ApiOperation(value = "读取资源", notes = "读取资源,通过ProcessDefinitionId")
    @GetMapping("/read")
    public void resourceRead(String id,
                             @RequestParam(required = false) String proInsId,
                             String type,
                             HttpServletResponse response) {
        log.info("读取资源, processDefinitionId:{}, proInsId:{}, type:{}", id, proInsId, type);
        byte[] bytes = actReProcdefService.readResource(id, proInsId, type);
        boolean image = "image".equalsIgnoreCase(type) || "svg".equalsIgnoreCase(type);
        response.setContentType(image ? "image/svg+xml;charset=UTF-8" : "application/xml;charset=UTF-8");
        try {
            response.getOutputStream().write(bytes);
            response.getOutputStream().flush();
        } catch (Exception e) {
            throw new JoinsoftException("读取资源文件失败", e);
        }
    }

    @ApiOperation(value = "部署流程文件", notes = "部署流程文件")
    @AutoLog("部署流程文件")
    @PostMapping("/deploy")
    public Result<?> deploy(@RequestParam("file") MultipartFile file) {
        return actReProcdefService.deploy(file);
    }

    @ApiOperation(value = "转模型", notes = "转模型")
    @AutoLog("转模型")
    @GetMapping("/convertToModel")
    public Result<?> convertToModel(String id) {
        return actReProcdefService.convertToModel(id);
    }

    @ApiOperation(value = "激活/挂起", notes = "激活/挂起")
    @AutoLog("激活/挂起")
    @PostMapping("/status")
    public Result<?> update(@RequestBody Map<String, Object> json) {
        String id = json.get("id") == null ? null : String.valueOf(json.get("id"));
        Integer state = json.get("state") == null ? null : Integer.valueOf(String.valueOf(json.get("state")));
        log.info("激活/挂起流程定义, id:{}, state:{}", id, state);
        return actReProcdefService.updateState(id, state);
    }

    @ApiOperation(value = "删除流程定义", notes = "删除流程定义(部署)")
    @AutoLog("删除流程定义")
    @PostMapping("/delete")
    public Result<?> delete(@RequestBody String[] deploymentIds) {
        log.info("删除流程定义, 参数:{}", (Object) deploymentIds);
        actReProcdefService.deleteBatch(deploymentIds);
        return Result.OK();
    }
}
