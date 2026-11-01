package com.joinsoft.modules.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.joinsoft.common.system.vo.LoginUser;
import com.joinsoft.modules.system.entity.SysUser;

import java.util.Collection;
import java.util.List;

public interface ISysUserService extends IService<SysUser> {

    SysUser getByUsername(String username);

    LoginUser getLoginUser(String userId);

    LoginUser toLoginUser(SysUser user);

    boolean matchesPassword(SysUser user, String rawPassword);

    String encodePassword(String rawPassword);

    /**
     * 按角色编码查询用户（role_codes 为 CSV）。
     */
    List<SysUser> listByRoleCodes(Collection<String> roleCodes);

    /**
     * 按单个角色编码查询用户。
     */
    List<SysUser> listByRoleCode(String roleCode);

    /**
     * 是否拥有下级（用于 Leader 审批人过滤）。
     */
    boolean isLeader(String userId);
}
