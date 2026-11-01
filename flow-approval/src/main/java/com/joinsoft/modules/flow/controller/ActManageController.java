package com.joinsoft.modules.flow.controller;

import com.joinsoft.common.api.vo.Result;
import com.joinsoft.common.exception.JoinsoftException;
import com.joinsoft.modules.flow.engine.ProcessModel;
import com.joinsoft.modules.flow.engine.ProcessNode;
import com.joinsoft.modules.flow.entity.ActReModelEntity;
import com.joinsoft.modules.flow.service.IActReModelService;
import com.joinsoft.modules.flow.service.impl.ModelService;
import io.swagger.annotations.Api;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * bpmn-js 设计器保存 / 预览接口，兼容原项目 {@code ActManageController}。
 */
@Api(value = "流程设计器", tags = "流程设计器")
@RestController
@RequestMapping("/service")
public class ActManageController {

    private final IActReModelService actReModelService;
    private final ModelService modelService;

    public ActManageController(IActReModelService actReModelService, ModelService modelService) {
        this.actReModelService = actReModelService;
        this.modelService = modelService;
    }

    /**
     * 保存设计器内容。请求体沿用原项目字段：process_xml / svg_xml。
     */
    @PostMapping("/model/{modelId}/saveBusi")
    public Result<?> saveModelBusi(@PathVariable String modelId, @RequestBody Map<String, String> map) {
        ActReModelEntity model = actReModelService.getById(modelId);
        if (model == null) {
            return Result.error("模板不存在");
        }
        String xml = map == null ? null : map.get("process_xml");
        if (!StringUtils.hasText(xml)) {
            return Result.error("缺少流程 XML");
        }
        ProcessModel parsed;
        try {
            parsed = modelService.requireModel(xml);
        } catch (Exception e) {
            return Result.error("流程 XML 解析失败：" + e.getMessage());
        }
        ActReModelEntity update = new ActReModelEntity();
        update.setId(model.getId());
        update.setBpmnXml(xml);
        update.setSvgXml(map.get("svg_xml"));
        actReModelService.edit(update);

        List<String> emptyNodes = new ArrayList<>();
        for (ProcessNode node : parsed.userTasks()) {
            boolean noAssignee = !StringUtils.hasText(node.getAssignee())
                    && node.getCandidateUsers().isEmpty()
                    && node.getCandidateGroups().isEmpty()
                    && node.getUserFilters().isEmpty();
            if (noAssignee) {
                emptyNodes.add(StringUtils.hasText(node.getName()) ? node.getName() : node.getId());
            }
        }
        String message = emptyNodes.isEmpty() ? "保存成功"
                : "存在" + emptyNodes.size() + "个节点未设置审批人：" + String.join(",", emptyNodes);
        return Result.OK(emptyNodes, message);
    }

    /**
     * 根据模型ID预览 BPMN XML。
     */
    @GetMapping("/viewXmlFromModelId")
    public Result<?> viewXmlFromModelId(@RequestParam("modelId") String modelId) throws Exception {
        ActReModelEntity model = actReModelService.getById(modelId);
        if (model == null) {
            throw new JoinsoftException("模板不存在");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("modelData", model);
        result.put("xmlContent", model.getBpmnXml());
        return Result.OK(result);
    }

    /**
     * 兼容原设计器的扩展属性列表。
     */
    @GetMapping("/model/{modelId}/nodes")
    public Result<?> nodes(@PathVariable String modelId) {
        ActReModelEntity model = actReModelService.getById(modelId);
        if (model == null) {
            return Result.error("模板不存在");
        }
        ProcessModel parsed = modelService.requireModel(model.getBpmnXml());
        List<Map<String, Object>> nodes = parsed.userTasks().stream().map(node -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", node.getId());
            item.put("name", node.getName());
            item.put("assignee", node.getAssignee());
            item.put("candidateUsers", node.getCandidateUsers());
            item.put("candidateGroups", node.getCandidateGroups());
            item.put("userFilters", node.getUserFilters());
            item.put("formKey", node.getFormKey());
            return item;
        }).collect(Collectors.toList());
        return Result.OK(nodes);
    }
}
