package com.joinsoft.modules.flow.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.joinsoft.common.api.vo.Result;
import com.joinsoft.modules.flow.entity.ActReModelEntity;
import com.joinsoft.modules.flow.entity.ActReProcdefEntity;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 流程定义（已发布快照）服务。
 */
public interface IActReProcdefService {

    Page<ActReProcdefEntity> queryPageList(ActReModelEntity query, Integer pageNo, Integer pageSize);

    List<ActReProcdefEntity> queryLastVersionList();

    byte[] readResource(String id, String proInsId, String type);

    Result<?> deploy(MultipartFile file);

    Result<?> convertToModel(String id);

    Result<?> updateState(String id, Integer state);

    void deleteBatch(String[] deploymentIds);
}
