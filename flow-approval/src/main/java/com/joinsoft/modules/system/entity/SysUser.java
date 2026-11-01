package com.joinsoft.modules.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.Date;

/**
 * 简化用户表：角色使用 role_codes CSV 表示，避免额外的用户-角色-部门关系表。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("sys_user")
public class SysUser implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;

    @TableField("username")
    private String username;

    @JsonIgnore
    @TableField("password")
    private String password;

    @TableField("realname")
    private String realname;

    @TableField("avatar")
    private String avatar;

    @TableField("email")
    private String email;

    @TableField("phone")
    private String phone;

    @TableField("dept_id")
    private String deptId;

    @TableField("role_codes")
    private String roleCodes;

    @TableField("leader_id")
    private String leaderId;

    @TableField("status")
    private Integer status;

    @TableField("create_time")
    private Date createTime;
}
