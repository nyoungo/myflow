package com.joinsoft.modules.flow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.joinsoft.common.api.vo.Result;
import com.joinsoft.common.exception.JoinsoftException;
import com.joinsoft.common.util.JsonUtils;
import com.joinsoft.modules.flow.engine.FlowEngine;
import com.joinsoft.modules.flow.engine.ProcessModel;
import com.joinsoft.modules.flow.engine.SvgDiagramGenerator;
import com.joinsoft.modules.flow.entity.ActBusinessData;
import com.joinsoft.modules.flow.entity.ActReModelEntity;
import com.joinsoft.modules.flow.entity.ActReProcdefEntity;
import com.joinsoft.modules.flow.mapper.ActBusinessDataMapper;
import com.joinsoft.modules.flow.mapper.ActReModelMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 流程定义（已发布快照）服务实现，数据来自 {@code flow_def} 中 {@code version>=1} 的行。
 */
@Slf4j
@Service
public class ActReProcdefServiceImpl implements com.joinsoft.modules.flow.service.IActReProcdefService {

    private final ActReModelMapper defMapper;
    private final ActBusinessDataMapper instanceMapper;
    private final ModelService modelService;
    private final FlowEngine engine;

    public ActReProcdefServiceImpl(ActReModelMapper defMapper, ActBusinessDataMapper instanceMapper,
                                   ModelService modelService, FlowEngine engine) {
        this.defMapper = defMapper;
        this.instanceMapper = instanceMapper;
        this.modelService = modelService;
        this.engine = engine;
    }

    @Override
    public Page<ActReProcdefEntity> queryPageList(ActReModelEntity query, Integer pageNo, Integer pageSize) {
        int current = pageNo == null || pageNo < 1 ? 1 : pageNo;
        int size = pageSize == null || pageSize < 1 ? 10 : pageSize;
        QueryWrapper<ActReModelEntity> wrapper = new QueryWrapper<ActReModelEntity>()
                .ge("version", 1)
                .orderByAsc("def_key")
                .orderByDesc("version");
        if (query != null && StringUtils.hasText(query.getName())) {
            wrapper.like("def_name", query.getName());
        }
        if (query != null && StringUtils.hasText(query.getKey())) {
            wrapper.eq("def_key", query.getKey());
        }
        Page<ActReModelEntity> rowPage = defMapper.selectPage(new Page<>(current, size), wrapper);
        Page<ActReProcdefEntity> result = new Page<>(current, size, rowPage.getTotal());
        List<ActReProcdefEntity> records = new ArrayList<>();
        for (ActReModelEntity row : rowPage.getRecords()) {
            records.add(convert(row));
        }
        result.setRecords(records);
        return result;
    }

    @Override
    public List<ActReProcdefEntity> queryLastVersionList() {
        List<ActReModelEntity> rows = defMapper.selectList(new QueryWrapper<ActReModelEntity>()
                .ge("version", 1)
                .orderByAsc("def_key")
                .orderByDesc("version"));
        Map<String, ActReModelEntity> latest = new LinkedHashMap<>();
        for (ActReModelEntity row : rows) {
            latest.putIfAbsent(row.getKey(), row);
        }
        List<ActReProcdefEntity> result = new ArrayList<>();
        for (ActReModelEntity row : latest.values()) {
            if (!"SUSPENDED".equals(row.getStatus())) {
                result.add(convert(row));
            }
        }
        return result;
    }

    @Override
    public byte[] readResource(String id, String proInsId, String type) {
        ActReModelEntity def = null;
        if (StringUtils.hasText(id)) {
            def = defMapper.selectById(id);
        } else if (StringUtils.hasText(proInsId)) {
            ActBusinessData instance = instanceMapper.selectById(proInsId);
            if (instance != null) {
                def = instance.getDefId() == null ? null : defMapper.selectById(instance.getDefId());
            }
        }
        if (def == null) {
            throw new JoinsoftException("流程定义不存在");
        }
        boolean image = "image".equalsIgnoreCase(type) || "svg".equalsIgnoreCase(type)
                || "png".equalsIgnoreCase(type);
        String content = image ? def.getSvgXml() : def.getBpmnXml();
        if (!StringUtils.hasText(content)) {
            content = image ? SvgDiagramGenerator.toBase64("") : "";
        }
        return content.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> deploy(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return Result.error("请选择要部署的流程文件");
        }
        String fileName = file.getOriginalFilename();
        if (!StringUtils.hasText(fileName)) {
            return Result.error("请选择要部署的流程文件");
        }
        String xml;
        try {
            xml = readBpmn(file, fileName);
        } catch (Exception e) {
            return Result.error("读取流程文件失败：" + e.getMessage());
        }
        if (!StringUtils.hasText(xml)) {
            return Result.error("压缩包中未找到 BPMN 文件");
        }
        ProcessModel parsed;
        try {
            parsed = modelService.requireModel(xml);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
        String key = StringUtils.hasText(parsed.getId()) ? parsed.getId() : stripExtension(fileName);
        String name = StringUtils.hasText(parsed.getName()) ? parsed.getName() : stripExtension(fileName);

        ActReModelEntity snapshot = new ActReModelEntity();
        snapshot.setId(IdWorker.getIdStr());
        snapshot.setRev(1);
        snapshot.setName(name);
        snapshot.setKey(key);
        snapshot.setCategory(StringUtils.hasText(parsed.getCategory()) ? parsed.getCategory() : "默认");
        snapshot.setDescription("上传部署：" + fileName);
        snapshot.setVersion(nextVersion(key));
        snapshot.setDeploymentId("deploy_" + IdWorker.getIdStr());
        snapshot.setStatus("PUBLISHED");
        snapshot.setBpmnXml(xml);
        snapshot.setSvgXml(SvgDiagramGenerator.generate(parsed, new java.util.HashSet<>(), new java.util.HashSet<>()));
        snapshot.setDeployTime(new Date());
        Date now = new Date();
        snapshot.setCreateTime(now);
        snapshot.setLastUpdateTime(now);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", name);
        meta.put("description", snapshot.getDescription());
        meta.put("revision", snapshot.getVersion());
        snapshot.setMetaInfo(JsonUtils.toJson(meta));
        defMapper.insert(snapshot);
        engine.evictAll();
        return Result.OK("部署成功", snapshot.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> convertToModel(String id) {
        ActReModelEntity source = defMapper.selectById(id);
        if (source == null) {
            return Result.error("流程定义不存在");
        }
        if (source.getVersion() == null || source.getVersion() < 1) {
            return Result.error("该记录已是草稿模板");
        }
        Long drafts = defMapper.selectCount(new QueryWrapper<ActReModelEntity>()
                .eq("def_key", source.getKey())
                .eq("version", 0));
        if (drafts != null && drafts > 0) {
            return Result.error("该流程已存在草稿模板，请直接在模板管理中编辑");
        }
        ActReModelEntity draft = new ActReModelEntity();
        draft.setId(IdWorker.getIdStr());
        draft.setRev(1);
        draft.setName(source.getName());
        draft.setKey(source.getKey());
        draft.setCategory(source.getCategory());
        draft.setDescription(source.getDescription());
        draft.setVersion(0);
        draft.setStatus("DRAFT");
        draft.setTenantId(source.getTenantId());
        draft.setBpmnXml(source.getBpmnXml());
        draft.setSvgXml(source.getSvgXml());
        draft.setMetaInfo(source.getMetaInfo());
        Date now = new Date();
        draft.setCreateTime(now);
        draft.setLastUpdateTime(now);
        defMapper.insert(draft);
        return Result.OK("转模型成功", draft.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> updateState(String id, Integer state) {
        ActReModelEntity def = defMapper.selectById(id);
        if (def == null) {
            return Result.error("流程定义不存在");
        }
        if (def.getVersion() == null || def.getVersion() < 1) {
            return Result.error("草稿模板不支持激活/挂起");
        }
        if (state != null && state == 1) {
            if (!"SUSPENDED".equals(def.getStatus())) {
                return Result.error("流程状态不能激活");
            }
            def.setStatus("PUBLISHED");
        } else if (state != null && state == 2) {
            if ("SUSPENDED".equals(def.getStatus())) {
                return Result.error("流程状态不能挂起");
            }
            def.setStatus("SUSPENDED");
        } else {
            return Result.error("无效的状态值：" + state);
        }
        def.setLastUpdateTime(new Date());
        defMapper.updateById(def);
        engine.evictAll();
        return Result.OK(state == 1 ? "已激活" : "已挂起");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteBatch(String[] deploymentIds) {
        if (deploymentIds == null) {
            return;
        }
        for (String deploymentId : deploymentIds) {
            if (!StringUtils.hasText(deploymentId)) {
                continue;
            }
            List<ActReModelEntity> rows = defMapper.selectList(new QueryWrapper<ActReModelEntity>()
                    .eq("deployment_id", deploymentId.trim())
                    .ge("version", 1));
            for (ActReModelEntity row : rows) {
                long used = instanceMapper.selectCount(new QueryWrapper<ActBusinessData>().eq("def_id", row.getId()));
                if (used > 0) {
                    throw new JoinsoftException("流程定义【" + row.getName() + "】存在流程实例，不能删除");
                }
                defMapper.deleteById(row.getId());
            }
        }
        engine.evictAll();
    }

    private ActReProcdefEntity convert(ActReModelEntity row) {
        ActReProcdefEntity entity = new ActReProcdefEntity();
        entity.setId(row.getId());
        entity.setRev(row.getRev());
        entity.setCategory(row.getCategory());
        entity.setName(row.getName());
        entity.setKey(row.getKey());
        entity.setVersion(row.getVersion());
        entity.setDeploymentId(row.getDeploymentId());
        entity.setDescription(row.getDescription());
        entity.setTenantId(row.getTenantId());
        entity.setStatus(row.getStatus());
        entity.setBpmnXml(row.getBpmnXml());
        entity.setSvgXml(row.getSvgXml());
        entity.setDeployTime(row.getDeployTime());
        entity.setResourceName(row.getKey() + ".bpmn20.xml");
        entity.setDgrmResourceName(row.getKey() + ".svg");
        entity.setHasGraphicalNotation("true");
        entity.setHasStartFormKey("false");
        entity.setEngineVersion("flow-1.0.0");
        return entity;
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

    private String readBpmn(MultipartFile file, String fileName) throws Exception {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".zip") || lower.endsWith(".bar")) {
            try (InputStream inputStream = file.getInputStream();
                 ZipInputStream zip = new ZipInputStream(inputStream, StandardCharsets.UTF_8)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String entryName = entry.getName() == null ? "" : entry.getName().toLowerCase();
                    if (entryName.endsWith(".bpmn20.xml") || entryName.endsWith(".bpmn")) {
                        return readAll(zip);
                    }
                }
            }
            return null;
        }
        if (lower.endsWith(".png")) {
            throw new JoinsoftException("暂不支持仅上传图片，请上传 BPMN XML");
        }
        return readAll(file.getInputStream());
    }

    private String readAll(InputStream inputStream) throws Exception {
        try (InputStream in = inputStream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int length;
            while ((length = in.read(buffer)) != -1) {
                out.write(buffer, 0, length);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private String stripExtension(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return "uploaded";
        }
        int index = fileName.lastIndexOf('.');
        return index > 0 ? fileName.substring(0, index) : fileName;
    }
}
