package com.joinsoft.modules.flow.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.joinsoft.modules.flow.entity.ActReModelEntity;

import javax.servlet.http.HttpServletResponse;
import java.util.Map;

/**
 * 流程模板服务，路径与行为参考原项目 {@code IActReModelService}。
 */
public interface IActReModelService {

    /**
     * 分页查询流程模板（草稿）。
     */
    IPage<ActReModelEntity> getWorkFlowList(Page<ActReModelEntity> page, QueryWrapper<ActReModelEntity> queryWrapper);

    /**
     * 原项目 bpmn-js 设计器分页接口。
     */
    Page<ActReModelEntity> queryPage(Map<String, Object> params);

    String save(ActReModelEntity model);

    String edit(ActReModelEntity model);

    /**
     * 发布草稿：生成 version>=1 的不可变快照。
     */
    ActReModelEntity deploy(String id);

    void export(String id, String type, HttpServletResponse response);

    void deleteBatch(String[] ids);

    void copy(String id, String sysOrgCode);

    void copyBatch(String ids, String sysOrgCode);

    IPage<ActReModelEntity> queryPageList(Page<ActReModelEntity> page, QueryWrapper<ActReModelEntity> queryWrapper);

    /**
     * 按主键或流程 key 查询模板。
     */
    ActReModelEntity getById(String key);
}
