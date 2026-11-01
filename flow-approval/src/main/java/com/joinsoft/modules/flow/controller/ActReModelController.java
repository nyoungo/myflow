package com.joinsoft.modules.flow.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.joinsoft.common.api.vo.Result;
import com.joinsoft.common.aspect.annotation.AutoLog;
import com.joinsoft.modules.flow.entity.ActReModelEntity;
import com.joinsoft.modules.flow.service.IActReModelService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;

/**
 * 流程模板管理接口，路径与原项目 {@code ActReModelController} 保持一致。
 */
@Api(value = "模型控制器", tags = "模型")
@Slf4j
@RestController
@RequestMapping("/act/model")
public class ActReModelController {

    private final IActReModelService actReModelService;

    public ActReModelController(IActReModelService actReModelService) {
        this.actReModelService = actReModelService;
    }

    @AutoLog(value = "模型管理-分页列表查询")
    @ApiOperation(value = "模型管理-分页列表查询", notes = "模型管理-分页列表查询")
    @GetMapping("/list")
    public Result<?> getWorkFlowList(@RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
                                     @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
                                     ActReModelEntity actReModel,
                                     HttpServletRequest req) {
        Page<ActReModelEntity> page = new Page<>(pageNo, pageSize);
        QueryWrapper<ActReModelEntity> wrapper = new QueryWrapper<>();
        if (actReModel != null) {
            if (StringUtils.hasText(actReModel.getName())) {
                wrapper.like("def_name", actReModel.getName());
            }
            if (StringUtils.hasText(actReModel.getKey())) {
                wrapper.eq("def_key", actReModel.getKey());
            }
            if (StringUtils.hasText(actReModel.getCategory())) {
                wrapper.eq("category", actReModel.getCategory());
            }
        }
        return Result.OK(actReModelService.getWorkFlowList(page, wrapper));
    }

    @ApiOperation(value = "分页查询接口", notes = "条件，分页查询")
    @GetMapping("/page")
    public Result<?> list(@RequestParam Map<String, Object> params) {
        log.info("分页查询所有流程模型接口，参数:{}", params);
        return Result.OK(actReModelService.queryPage(params));
    }

    @ApiOperation(value = "新增", notes = "新增")
    @AutoLog("新增流程模型")
    @PostMapping("/save")
    public Result<?> save(@RequestBody ActReModelEntity actReModel) {
        String id = actReModelService.save(actReModel);
        return Result.OK(id, "添加成功");
    }

    @ApiOperation(value = "编辑", notes = "编辑")
    @AutoLog("编辑")
    @PutMapping("/edit")
    public Result<?> edit(@RequestBody ActReModelEntity actReModel) {
        actReModelService.edit(actReModel);
        return Result.OK("编辑成功");
    }

    @ApiOperation(value = "部署", notes = "发布草稿为流程定义")
    @AutoLog("部署流程文件")
    @GetMapping("/deploy")
    public Result<?> deploy(String id) {
        ActReModelEntity snapshot = actReModelService.deploy(id);
        return Result.OK(snapshot, "发布成功");
    }

    @ApiOperation(value = "导出model的xml文件", notes = "导出model的xml文件")
    @RequestMapping("/export")
    public void export(String id, @RequestParam(defaultValue = "xml", required = false) String type,
                       HttpServletResponse response) {
        actReModelService.export(id, type, response);
    }

    @AutoLog(value = "模型管理-通过id删除")
    @ApiOperation(value = "模型管理-通过id删除", notes = "模型管理-通过id删除")
    @DeleteMapping("/delete")
    public Result<?> delete(@RequestParam(name = "id") String id) {
        actReModelService.deleteBatch(new String[]{id});
        return Result.OK("删除成功!");
    }

    @ApiOperation(value = "复制工作流模板", notes = "复制工作流模板")
    @AutoLog("复制工作流模板")
    @GetMapping("/copy")
    public Result<?> copy(@RequestParam(name = "id") String id,
                          @RequestParam(name = "sysOrgCode", required = false) String sysOrgCode) {
        actReModelService.copy(id, sysOrgCode);
        return Result.OK("复制成功");
    }

    @ApiOperation(value = "批量复制工作流模板", notes = "批量复制工作流模板")
    @AutoLog("批量复制工作流模板")
    @GetMapping("/copyBatch")
    public Result<?> copyBatch(@RequestParam(name = "ids") String ids,
                               @RequestParam(name = "sysOrgCode", required = false) String sysOrgCode) {
        actReModelService.copyBatch(ids, sysOrgCode);
        return Result.OK("复制成功");
    }

    @AutoLog(value = "分页列表查询（业务流程模板表）")
    @ApiOperation(value = "分页列表查询（业务流程模板表）", notes = "分页列表查询（业务流程模板表）")
    @GetMapping("/queryPageList")
    public Result<?> queryPageList(@RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
                                   @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
                                   ActReModelEntity actReModel,
                                   HttpServletRequest req) {
        Page<ActReModelEntity> page = new Page<>(pageNo, pageSize);
        QueryWrapper<ActReModelEntity> wrapper = new QueryWrapper<>();
        if (actReModel != null) {
            if (StringUtils.hasText(actReModel.getName())) {
                wrapper.like("def_name", actReModel.getName());
            }
            if (StringUtils.hasText(actReModel.getKey())) {
                wrapper.eq("def_key", actReModel.getKey());
            }
        }
        return Result.OK(actReModelService.queryPageList(page, wrapper));
    }

    @AutoLog(value = "根据模板id查询流程模板信息")
    @ApiOperation(value = "根据模板id查询流程模板信息", notes = "根据模板id查询流程模板信息")
    @GetMapping("/queryById")
    public Result<?> queryById(@RequestParam("key") String key) {
        return Result.OK(actReModelService.getById(key));
    }
}
