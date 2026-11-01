package com.joinsoft.modules.flow.util;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.joinsoft.common.api.vo.Result;
import com.joinsoft.common.util.SpringContextHolder;
import com.joinsoft.modules.flow.dto.StartData;
import com.joinsoft.modules.flow.entity.ActBusinessData;
import com.joinsoft.modules.flow.service.IActBusinessDataService;
import com.joinsoft.modules.flow.service.IFlowService;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工作流工具类，方法签名与实现语义与原项目保持一致。
 */
public class FlowUtil {

    /**
     * 启动工作流。
     */
    public static Result<?> startFlow(StartData startData) {
        IFlowService flowService = SpringContextHolder.getBean(IFlowService.class);
        return flowService.start(startData);
    }

    /**
     * 处理分页数据，把流程信息写入业务实体父类的 businessData 字段。
     */
    public static <T> IPage<?> handlePageList(IPage<T> page) {
        if (page.getTotal() == 0) {
            return page;
        }
        List<String> idList = page.getRecords().stream().map(item -> {
            try {
                Method getIdMethod = item.getClass().getMethod("getId");
                return (String) getIdMethod.invoke(item);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).collect(Collectors.toList());

        IActBusinessDataService businessDataService = SpringContextHolder.getBean(IActBusinessDataService.class);
        List<ActBusinessData> businessDataList = businessDataService.getByBusinessIds(idList);

        for (Object record : page.getRecords()) {
            try {
                Field businessDataField = record.getClass().getSuperclass().getDeclaredField("businessData");
                businessDataField.setAccessible(true);

                Field idField = record.getClass().getDeclaredField("id");
                idField.setAccessible(true);
                String id = idField.get(record).toString();

                List<ActBusinessData> collect = businessDataList.stream()
                        .filter(item -> item.getBusinessId().equals(id))
                        .collect(Collectors.toList());
                if (!collect.isEmpty()) {
                    businessDataField.set(record, collect.get(0));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return page;
    }

    public static Map<String, Object> objectToMap(Object obj) {
        ObjectMapper objectMapper = new ObjectMapper();
        return objectMapper.convertValue(obj, Map.class);
    }
}
