package com.joinsoft.common.system.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 登录用户信息，字段与原 jeecg-boot {@code LoginUser} 保持兼容。
 */
@Data
public class LoginUser implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;
    private String username;
    private String realname;
    private String avatar;
    private String email;
    private String phone;
    private String deptId;
    private String leaderId;
    /** 角色编码，形如 {@code ,admin,manager,} */
    private String roleCodes;
    /** 兼容字段：部门名称 */
    private String orgCode;

    @JsonIgnore
    public List<String> getRoleCodeList() {
        if (roleCodes == null || roleCodes.trim().isEmpty()) {
            return new ArrayList<>();
        }
        return Arrays.stream(roleCodes.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }

    public boolean hasRole(String roleCode) {
        return getRoleCodeList().contains(roleCode);
    }
}
