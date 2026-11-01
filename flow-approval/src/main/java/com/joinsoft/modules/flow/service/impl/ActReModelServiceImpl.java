package com.joinsoft.modules.flow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.joinsoft.common.exception.JoinsoftException;
import com.joinsoft.common.util.DateUtils;
import com.joinsoft.common.util.JsonUtils;
import com.joinsoft.modules.flow.engine.FlowEngine;
import com.joinsoft.modules.flow.engine.ProcessModel;
import com.joinsoft.modules.flow.engine.SvgDiagramGenerator;
import com.joinsoft.modules.flow.entity.ActBusinessData;
import com.joinsoft.modules.flow.entity.ActReModelEntity;
import com.joinsoft.modules.flow.mapper.ActBusinessDataMapper;
import com.joinsoft.modules.flow.mapper.ActReModelMapper;
import com.joinsoft.modules.flow.service.IActReModelService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.servlet.http.HttpServletResponse;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流程模板服务实现。
 *
 * <p>草稿与已发布快照共用 {@code flow_def}：草稿 {@code version=0}，
 * 每次发布生成一条 {@code version>=1} 的不可变快照。</p>
 */
@Slf4j
@Service
public class ActReModelServiceImpl implements IActReModelService {

    private final ActReModelMapper defMapper;
    private final ActBusinessDataMapper instanceMapper;
    private final ModelService modelService;
    private final FlowEngine engine;

    public ActReModelServiceImpl(ActReModelMapper defMapper, ActBusinessDataMapper instanceMapper,
                                 ModelService modelService, FlowEngine engine) {
        this.defMapper = defMapper;
        this.instanceMapper = instanceMapper;
        this.modelService = modelService;
        this.engine = engine;
    }

    @Override
    public IPage<ActReModelEntity> getWorkFlowList(Page<ActReModelEntity> page,
                                                   QueryWrapper<ActReModelEntity> queryWrapper) {
        QueryWrapper<ActReModelEntity> wrapper = queryWrapper == null
                ? new QueryWrapper<>() : queryWrapper;
        wrapper.eq("version", 0).orderByDesc("update_time");
        IPage<ActReModelEntity> result = defMapper.selectPage(page, wrapper);
        fillDeployTime(result.getRecords());
        return result;
    }

    @Override
    public Page<ActReModelEntity> queryPage(Map<String, Object> params) {
        int current = intValue(params.get("page"), 1);
        int size = intValue(params.get("limit"), 10);
        String key = stringValue(params.get("key"));
        String name = stringValue(params.get("name"));
        QueryWrapper<ActReModelEntity> wrapper = new QueryWrapper<ActReModelEntity>()
                .eq("version", 0)
                .orderByDesc("update_time");
        if (StringUtils.hasText(key)) {
            wrapper.eq("def_key", key);
        }
        if (StringUtils.hasText(name)) {
            wrapper.like("def_name", name);
        }
        Page<ActReModelEntity> page = defMapper.selectPage(new Page<>(current, size), wrapper);
        fillDeployTime(page.getRecords());
        return page;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String save(ActReModelEntity model) {
        if (model == null || !StringUtils.hasText(model.getName())) {
            throw new JoinsoftException("模板名称不能为空");
        }
        String id = StringUtils.hasText(model.getId()) ? model.getId() : IdWorker.getIdStr();
        String key = StringUtils.hasText(model.getKey()) ? model.getKey() : "flow_" + id;
        ensureKeyAvailable(key, null);
        String bpmn = StringUtils.hasText(model.getBpmnXml()) ? model.getBpmnXml() : defaultBpmn(key, model.getName());
        ProcessModel parsed = modelService.requireModel(bpmn);

        model.setId(id);
        model.setKey(key);
        model.setVersion(0);
        model.setRev(1);
        model.setStatus("DRAFT");
        model.setBpmnXml(bpmn);
        model.setSvgXml(SvgDiagramGenerator.generate(parsed, new java.util.HashSet<>(), new java.util.HashSet<>()));
        model.setMetaInfo(metaInfo(model.getName(), model.getDescription(), 1));
        if (!StringUtils.hasText(model.getCategory())) {
            model.setCategory("默认");
        }
        Date now = new Date();
        model.setCreateTime(now);
        model.setLastUpdateTime(now);
        defMapper.insert(model);
        return id;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String edit(ActReModelEntity model) {
        if (model == null || !StringUtils.hasText(model.getId())) {
            throw new JoinsoftException("缺少模板ID");
        }
        ActReModelEntity current = defMapper.selectById(model.getId());
        if (current == null) {
            throw new JoinsoftException("模板不存在");
        }
        if (current.getVersion() != null && current.getVersion() >= 1) {
            throw new JoinsoftException("已发布的流程不可直接编辑，请先转为模型");
        }
        String key = StringUtils.hasText(model.getKey()) ? model.getKey() : current.getKey();
        if (!key.equals(current.getKey())) {
            ensureKeyAvailable(key, current.getId());
        }
        String bpmn = StringUtils.hasText(model.getBpmnXml()) ? model.getBpmnXml() : current.getBpmnXml();
        ProcessModel parsed = modelService.requireModel(bpmn);

        current.setName(StringUtils.hasText(model.getName()) ? model.getName() : current.getName());
        current.setKey(key);
        current.setCategory(StringUtils.hasText(model.getCategory()) ? model.getCategory() : current.getCategory());
        current.setDescription(model.getDescription() == null ? current.getDescription() : model.getDescription());
        current.setBpmnXml(bpmn);
        current.setSvgXml(SvgDiagramGenerator.generate(parsed, new java.util.HashSet<>(), new java.util.HashSet<>()));
        current.setMetaInfo(metaInfo(current.getName(), current.getDescription(),
                current.getVersion() == null ? 0 : current.getVersion() + 1));
        current.setRev(current.getRev() == null ? 1 : current.getRev() + 1);
        current.setLastUpdateTime(new Date());
        defMapper.updateById(current);
        return current.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ActReModelEntity deploy(String id) {
        ActReModelEntity draft = defMapper.selectById(id);
        if (draft == null) {
            throw new JoinsoftException("模板不存在");
        }
        if (draft.getVersion() != null && draft.getVersion() >= 1) {
            throw new JoinsoftException("该流程已发布，请编辑草稿后再发布");
        }
        ProcessModel parsed = modelService.requireModel(draft.getBpmnXml());
        int nextVersion = nextVersion(draft.getKey());

        ActReModelEntity snapshot = new ActReModelEntity();
        snapshot.setId(IdWorker.getIdStr());
        snapshot.setRev(1);
        snapshot.setName(draft.getName());
        snapshot.setKey(draft.getKey());
        snapshot.setCategory(draft.getCategory());
        snapshot.setDescription(draft.getDescription());
        snapshot.setCreateTime(new Date());
        snapshot.setLastUpdateTime(new Date());
        snapshot.setVersion(nextVersion);
        snapshot.setDeploymentId("deploy_" + IdWorker.getIdStr());
        snapshot.setTenantId(draft.getTenantId());
        snapshot.setStatus("PUBLISHED");
        snapshot.setBpmnXml(draft.getBpmnXml());
        snapshot.setSvgXml(SvgDiagramGenerator.generate(parsed, new java.util.HashSet<>(), new java.util.HashSet<>()));
        snapshot.setDeployTime(new Date());
        snapshot.setMetaInfo(draft.getMetaInfo());
        defMapper.insert(snapshot);

        draft.setLastUpdateTime(new Date());
        draft.setRev(draft.getRev() == null ? 1 : draft.getRev() + 1);
        defMapper.updateById(draft);
        // 已发布快照会进入引擎缓存，清空以加载最新 XML
        modelServiceGroupClean();
        return snapshot;
    }

    @Override
    public void export(String id, String type, HttpServletResponse response) {
        ActReModelEntity def = defMapper.selectById(id);
        if (def == null) {
            throw new JoinsoftException("模板不存在");
        }
        String content;
        String filename;
        if ("json".equalsIgnoreCase(type)) {
            Map<String, Object> editor = new LinkedHashMap<>();
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("process_id", def.getKey());
            properties.put("name", def.getName());
            properties.put("documentation", def.getDescription());
            editor.put("id", "canvas");
            editor.put("resourceId", "canvas");
            editor.put("properties", properties);
            Map<String, Object> stencil = new LinkedHashMap<>();
            stencil.put("namespace", "http://b3mn.org/stencilset/bpmn2.0#");
            editor.put("stencilset", stencil);
            content = JsonUtils.toJson(editor);
            filename = safeFilename(def.getKey()) + ".json";
        } else {
            content = def.getBpmnXml() == null ? defaultBpmn(def.getKey(), def.getName()) : def.getBpmnXml();
            filename = safeFilename(def.getKey()) + ".bpmn20.xml";
        }
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/octet-stream");
        try {
            response.setHeader("Content-Disposition", "attachment; filename="
                    + URLEncoder.encode(filename, "UTF-8").replace("+", "%20"));
            OutputStream outputStream = response.getOutputStream();
            outputStream.write(content.getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        } catch (Exception e) {
            throw new JoinsoftException("导出模板失败", e);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteBatch(String[] ids) {
        if (ids == null) {
            return;
        }
        for (String id : ids) {
            if (!StringUtils.hasText(id)) {
                continue;
            }
            ActReModelEntity def = defMapper.selectById(id);
            if (def == null) {
                continue;
            }
            long used = instanceMapper.selectCount(new QueryWrapper<ActBusinessData>().eq("def_id", id));
            if (used > 0) {
                throw new JoinsoftException("模板【" + def.getName() + "】存在流程实例，不能删除");
            }
            defMapper.deleteById(id);
        }
        modelServiceGroupClean();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void copy(String id, String sysOrgCode) {
        ActReModelEntity source = defMapper.selectById(id);
        if (source == null) {
            throw new JoinsoftException("模板不存在");
        }
        String suffix = StringUtils.hasText(sysOrgCode) ? "_" + sysOrgCode : "_copy";
        String key = source.getKey() + suffix;
        ensureKeyAvailable(key, null);
        ActReModelEntity copy = new ActReModelEntity();
        copy.setId(IdWorker.getIdStr());
        copy.setRev(1);
        copy.setName(source.getName() + suffix);
        copy.setKey(key);
        copy.setCategory(source.getCategory());
        copy.setDescription(source.getDescription());
        copy.setVersion(0);
        copy.setStatus("DRAFT");
        copy.setTenantId(StringUtils.hasText(sysOrgCode) ? sysOrgCode : source.getTenantId());
        copy.setBpmnXml(replaceProcessId(source.getBpmnXml(), key));
        copy.setSvgXml(source.getSvgXml());
        copy.setMetaInfo(metaInfo(copy.getName(), copy.getDescription(), 1));
        Date now = new Date();
        copy.setCreateTime(now);
        copy.setLastUpdateTime(now);
        defMapper.insert(copy);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void copyBatch(String ids, String sysOrgCode) {
        if (!StringUtils.hasText(ids)) {
            throw new JoinsoftException("请选择模板");
        }
        for (String id : ids.split(",")) {
            if (StringUtils.hasText(id)) {
                copy(id.trim(), sysOrgCode);
            }
        }
    }

    @Override
    public IPage<ActReModelEntity> queryPageList(Page<ActReModelEntity> page,
                                                 QueryWrapper<ActReModelEntity> queryWrapper) {
        return getWorkFlowList(page, queryWrapper);
    }

    @Override
    public ActReModelEntity getById(String key) {
        if (!StringUtils.hasText(key)) {
            return null;
        }
        ActReModelEntity def = defMapper.selectById(key);
        if (def != null) {
            return def;
        }
        List<ActReModelEntity> list = defMapper.selectList(new QueryWrapper<ActReModelEntity>()
                .eq("def_key", key)
                .orderByDesc("version")
                .last("limit 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private void fillDeployTime(List<ActReModelEntity> records) {
        if (records == null) {
            return;
        }
        for (ActReModelEntity record : records) {
            List<ActReModelEntity> published = defMapper.selectList(new QueryWrapper<ActReModelEntity>()
                    .eq("def_key", record.getKey())
                    .ge("version", 1)
                    .orderByDesc("version")
                    .last("limit 1"));
            if (!published.isEmpty()) {
                record.setDeployTime(published.get(0).getDeployTime());
            }
        }
    }

    private int nextVersion(String key) {
        List<ActReModelEntity> list = defMapper.selectList(new QueryWrapper<ActReModelEntity>()
                .eq("def_key", key)
                .orderByDesc("version")
                .last("limit 1"));
        if (list.isEmpty() || list.get(0).getVersion() == null) {
            return 1;
        }
        return Math.max(1, list.get(0).getVersion() + 1);
    }

    private void ensureKeyAvailable(String key, String excludeId) {
        QueryWrapper<ActReModelEntity> wrapper = new QueryWrapper<ActReModelEntity>()
                .eq("def_key", key);
        if (StringUtils.hasText(excludeId)) {
            wrapper.ne("id", excludeId);
        }
        if (defMapper.selectCount(wrapper) > 0) {
            throw new JoinsoftException("流程标识已存在：" + key);
        }
    }

    private String metaInfo(String name, String description, int revision) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", name);
        meta.put("description", description == null ? "" : description);
        meta.put("revision", revision);
        return JsonUtils.toJson(meta);
    }

    private String safeFilename(String name) {
        return (StringUtils.hasText(name) ? name : "process").replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private int intValue(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String replaceProcessId(String bpmn, String key) {
        if (!StringUtils.hasText(bpmn)) {
            return bpmn;
        }
        return bpmn.replaceAll("(<process[^>]*\\sid=\")([^\"]*)(\")", "$1" + key + "$3");
    }

    /** 发布 / 删除后清空引擎模型缓存。 */
    private void modelServiceGroupClean() {
        engine.evictAll();
    }

    private String defaultBpmn(String key, String name) {
        String safeKey = escape(key);
        String safeName = escape(name);
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
                + "xmlns:bpmndi=\"http://www.omg.org/spec/BPMN/20100524/DI\" "
                + "xmlns:dc=\"http://www.omg.org/spec/DD/20100524/DC\" "
                + "xmlns:di=\"http://www.omg.org/spec/DD/20100524/DI\" "
                + "targetNamespace=\"http://joinsoft.com/flow\">\n"
                + "  <process id=\"" + safeKey + "\" name=\"" + safeName + "\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"start\" name=\"开始\"/>\n"
                + "    <userTask id=\"approve\" name=\"审批\" assignee=\"admin\"/>\n"
                + "    <endEvent id=\"end\" name=\"结束\"/>\n"
                + "    <sequenceFlow id=\"flow1\" sourceRef=\"start\" targetRef=\"approve\"/>\n"
                + "    <sequenceFlow id=\"flow2\" sourceRef=\"approve\" targetRef=\"end\"/>\n"
                + "  </process>\n"
                + "  <bpmndi:BPMNDiagram id=\"BPMNDiagram_1\">\n"
                + "    <bpmndi:BPMNPlane id=\"BPMNPlane_1\" bpmnElement=\"" + safeKey + "\">\n"
                + "      <bpmndi:BPMNShape id=\"shape_start\" bpmnElement=\"start\">\n"
                + "        <dc:Bounds x=\"50\" y=\"150\" width=\"36\" height=\"36\"/>\n"
                + "      </bpmndi:BPMNShape>\n"
                + "      <bpmndi:BPMNShape id=\"shape_approve\" bpmnElement=\"approve\">\n"
                + "        <dc:Bounds x=\"140\" y=\"128\" width=\"120\" height=\"80\"/>\n"
                + "      </bpmndi:BPMNShape>\n"
                + "      <bpmndi:BPMNShape id=\"shape_end\" bpmnElement=\"end\">\n"
                + "        <dc:Bounds x=\"320\" y=\"150\" width=\"36\" height=\"36\"/>\n"
                + "      </bpmndi:BPMNShape>\n"
                + "      <bpmndi:BPMNEdge id=\"edge_flow1\" bpmnElement=\"flow1\">\n"
                + "        <di:waypoint x=\"86\" y=\"168\"/>\n"
                + "        <di:waypoint x=\"140\" y=\"168\"/>\n"
                + "      </bpmndi:BPMNEdge>\n"
                + "      <bpmndi:BPMNEdge id=\"edge_flow2\" bpmnElement=\"flow2\">\n"
                + "        <di:waypoint x=\"260\" y=\"168\"/>\n"
                + "        <di:waypoint x=\"320\" y=\"168\"/>\n"
                + "      </bpmndi:BPMNEdge>\n"
                + "    </bpmndi:BPMNPlane>\n"
                + "  </bpmndi:BPMNDiagram>\n"
                + "</definitions>";
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
