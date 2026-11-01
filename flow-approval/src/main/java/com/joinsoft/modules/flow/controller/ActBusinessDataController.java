package com.joinsoft.modules.flow.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.joinsoft.common.api.vo.Result;
import com.joinsoft.common.aspect.annotation.AutoLog;
import com.joinsoft.modules.flow.entity.ActBusinessData;
import com.joinsoft.modules.flow.service.IActBusinessDataService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流实例 / 业务主表接口，路径与参数名与原项目保持一致。
 */
@Slf4j
@Api(tags = "工作流业务主表")
@RestController
@RequestMapping("/flow/actBusinessData")
public class ActBusinessDataController {

    @Autowired
    private IActBusinessDataService actBusinessDataService;

    @AutoLog(value = "工作流业务主表-分页列表查询")
    @ApiOperation(value = "工作流业务主表-分页列表查询", notes = "工作流业务主表-分页列表查询")
    @GetMapping("/list")
    public Result<?> queryPageList(ActBusinessData actBusinessData,
                                   @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
                                   @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize) {
        Page<ActBusinessData> page = new Page<>(pageNo, pageSize);
        QueryWrapper<ActBusinessData> wrapper = new QueryWrapper<ActBusinessData>()
                .orderByDesc("start_time");
        if (actBusinessData != null) {
            if (StringUtils.hasText(actBusinessData.getTitle())) {
                wrapper.like("title", actBusinessData.getTitle());
            }
            if (StringUtils.hasText(actBusinessData.getState())) {
                wrapper.eq("state", actBusinessData.getState());
            }
            if (StringUtils.hasText(actBusinessData.getBusinessId())) {
                wrapper.eq("business_id", actBusinessData.getBusinessId());
            }
            if (StringUtils.hasText(actBusinessData.getDefKey())) {
                wrapper.eq("def_key", actBusinessData.getDefKey());
            }
            if (StringUtils.hasText(actBusinessData.getStartUserId())) {
                wrapper.eq("start_user_id", actBusinessData.getStartUserId());
            }
        }
        IPage<ActBusinessData> pageList = actBusinessDataService.page(page, wrapper);
        return Result.OK(pageList);
    }

    @AutoLog(value = "工作流待办业务主表-分页列表查询")
    @ApiOperation(value = "工作流待办业务主表-分页列表查询", notes = "工作流待办业务主表-分页列表查询")
    @GetMapping("/todoList")
    public Result<?> queryTodoList(ActBusinessData actBusinessData,
                                   @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
                                   @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize) {
        Page<Map<String, Object>> page = new Page<>(pageNo, pageSize);
        return actBusinessDataService.queryTodoList(page, actBusinessData);
    }

    @AutoLog(value = "工作流已办业务主表-分页列表查询")
    @ApiOperation(value = "工作流已办业务主表-分页列表查询", notes = "工作流已办业务主表-分页列表查询")
    @GetMapping("/doneList")
    public Result<?> queryDoneList(ActBusinessData actBusinessData,
                                   @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
                                   @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize) {
        Page<Map<String, Object>> page = new Page<>(pageNo, pageSize);
        return actBusinessDataService.queryDoneList(page, actBusinessData);
    }

    @AutoLog(value = "工作流任务管理-分页列表查询")
    @ApiOperation(value = "工作流任务管理-分页列表查询", notes = "工作流任务管理-分页列表查询")
    @GetMapping("/allList")
    public Result<?> queryAllList(ActBusinessData actBusinessData,
                                  @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
                                  @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize) {
        Page<Map<String, Object>> page = new Page<>(pageNo, pageSize);
        return actBusinessDataService.queryAllList(page, actBusinessData);
    }

    @AutoLog(value = "工作流我的申请业务主表-分页列表查询")
    @ApiOperation(value = "工作流我的申请业务主表-分页列表查询", notes = "工作流我的申请业务主表-分页列表查询")
    @GetMapping("/myApplyAList")
    public Result<?> queryMyApplyList(ActBusinessData actBusinessData,
                                      @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
                                      @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize) {
        Page<Map<String, Object>> page = new Page<>(pageNo, pageSize);
        return actBusinessDataService.queryMyApplyList(page, actBusinessData);
    }

    @AutoLog(value = "工作流流程退回-分页列表查询")
    @ApiOperation(value = "工作流流程退回-分页列表查询", notes = "工作流流程退回-分页列表查询")
    @GetMapping("/backList")
    public Result<?> backList(ActBusinessData actBusinessData,
                              @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
                              @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize) {
        Page<Map<String, Object>> page = new Page<>(pageNo, pageSize);
        return actBusinessDataService.queryBackList(page, actBusinessData);
    }

    @AutoLog(value = "工作流业务主表-添加")
    @ApiOperation(value = "工作流业务主表-添加", notes = "工作流业务主表-添加")
    @PostMapping("/add")
    public Result<?> add(@RequestBody ActBusinessData actBusinessData) {
        actBusinessDataService.save(actBusinessData);
        return Result.OK("添加成功！");
    }

    @AutoLog(value = "工作流业务主表-编辑")
    @ApiOperation(value = "工作流业务主表-编辑", notes = "工作流业务主表-编辑")
    @PutMapping("/edit")
    public Result<?> edit(@RequestBody ActBusinessData actBusinessData) {
        actBusinessDataService.updateById(actBusinessData);
        return Result.OK("编辑成功!");
    }

    @AutoLog(value = "工作流业务主表-通过id删除")
    @ApiOperation(value = "工作流业务主表-通过id删除", notes = "工作流业务主表-通过id删除")
    @DeleteMapping("/delete")
    public Result<?> delete(@RequestParam(name = "id", required = true) String id) {
        actBusinessDataService.removeById(id);
        return Result.OK("删除成功!");
    }

    @AutoLog(value = "工作流业务主表-批量删除")
    @ApiOperation(value = "工作流业务主表-批量删除", notes = "工作流业务主表-批量删除")
    @DeleteMapping("/deleteBatch")
    public Result<?> deleteBatch(@RequestParam(name = "ids", required = true) String ids) {
        actBusinessDataService.removeByIds(Arrays.asList(ids.split(",")));
        return Result.OK("批量删除成功！");
    }

    @AutoLog(value = "工作流业务主表-通过id查询")
    @ApiOperation(value = "工作流业务主表-通过id查询", notes = "工作流业务主表-通过id查询")
    @GetMapping("/queryById")
    public Result<?> queryById(@RequestParam(name = "id", required = true) String id) {
        return Result.OK(actBusinessDataService.getById(id));
    }

    @ApiOperation(value = "导出excel", notes = "导出工作流实例")
    @RequestMapping("/exportXls")
    public void exportXls(HttpServletResponse response) {
        List<ActBusinessData> list = actBusinessDataService.list(new QueryWrapper<ActBusinessData>()
                .orderByDesc("start_time"));
        String[] headers = {"ID", "业务ID", "业务KEY", "标题", "流程名称", "状态", "发起人", "开始时间", "结束时间"};
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("工作流业务主表");
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
                sheet.setColumnWidth(i, 20 * 256);
            }
            int index = 1;
            for (ActBusinessData item : list) {
                Row row = sheet.createRow(index++);
                row.createCell(0).setCellValue(text(item.getId()));
                row.createCell(1).setCellValue(text(item.getBusinessId()));
                row.createCell(2).setCellValue(text(item.getBusinessKey()));
                row.createCell(3).setCellValue(text(item.getTitle()));
                row.createCell(4).setCellValue(text(item.getDefName()));
                row.createCell(5).setCellValue(text(item.getState()));
                row.createCell(6).setCellValue(text(item.getStartUserName()));
                row.createCell(7).setCellValue(item.getStartTime() == null ? "" : item.getStartTime().toString());
                row.createCell(8).setCellValue(item.getEndTime() == null ? "" : item.getEndTime().toString());
            }
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Content-Disposition", "attachment; filename="
                    + URLEncoder.encode("工作流业务主表.xlsx", "UTF-8").replace("+", "%20"));
            try (OutputStream outputStream = response.getOutputStream()) {
                workbook.write(outputStream);
                outputStream.flush();
            }
        } catch (Exception e) {
            log.error("导出工作流业务主表失败", e);
        }
    }

    @ApiOperation(value = "通过excel导入数据", notes = "通过excel导入工作流实例")
    @PostMapping("/importExcel")
    public Result<?> importExcel(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return Result.error("请选择要导入的文件");
        }
        List<ActBusinessData> list = new ArrayList<>();
        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                ActBusinessData item = new ActBusinessData();
                item.setId(cell(row, 0));
                item.setBusinessId(cell(row, 1));
                item.setBusinessKey(cell(row, 2));
                item.setTitle(cell(row, 3));
                item.setDefName(cell(row, 4));
                item.setState(cell(row, 5));
                item.setStartUserName(cell(row, 6));
                item.setStartTime(new Date());
                list.add(item);
            }
        } catch (Exception e) {
            return Result.error("解析 Excel 失败：" + e.getMessage());
        }
        if (list.isEmpty()) {
            return Result.error("Excel 中没有可导入的数据");
        }
        for (ActBusinessData item : list) {
            if (StringUtils.hasText(item.getId())) {
                actBusinessDataService.saveOrUpdate(item);
            } else {
                actBusinessDataService.save(item);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", list.size());
        return Result.OK(result, "导入成功");
    }

    private String cell(Row row, int index) {
        Cell cell = row.getCell(index);
        if (cell == null) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            return String.valueOf((long) cell.getNumericCellValue());
        }
        String value = cell.getStringCellValue();
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private String text(String value) {
        return value == null ? "" : value;
    }
}
