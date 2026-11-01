package com.joinsoft.modules.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.joinsoft.common.system.vo.LoginUser;
import com.joinsoft.modules.system.entity.SysUser;
import com.joinsoft.modules.system.mapper.SysUserMapper;
import com.joinsoft.modules.system.service.ISysUserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements ISysUserService {

    @Value("${flow.security.password-salt:flow-approval}")
    private String passwordSalt;

    @Override
    public SysUser getByUsername(String username) {
        if (!StringUtils.hasText(username)) {
            return null;
        }
        return getOne(new QueryWrapper<SysUser>().eq("username", username).last("limit 1"), false);
    }

    @Override
    public LoginUser getLoginUser(String userId) {
        if (!StringUtils.hasText(userId)) {
            return null;
        }
        SysUser user = getById(userId);
        return user == null ? null : toLoginUser(user);
    }

    @Override
    public LoginUser toLoginUser(SysUser user) {
        LoginUser loginUser = new LoginUser();
        loginUser.setId(user.getId());
        loginUser.setUsername(user.getUsername());
        loginUser.setRealname(user.getRealname());
        loginUser.setAvatar(user.getAvatar());
        loginUser.setEmail(user.getEmail());
        loginUser.setPhone(user.getPhone());
        loginUser.setDeptId(user.getDeptId());
        loginUser.setLeaderId(user.getLeaderId());
        loginUser.setRoleCodes(normalizeCsv(user.getRoleCodes()));
        return loginUser;
    }

    @Override
    public boolean matchesPassword(SysUser user, String rawPassword) {
        if (user == null || rawPassword == null) {
            return false;
        }
        return encodePassword(rawPassword).equalsIgnoreCase(user.getPassword());
    }

    @Override
    public String encodePassword(String rawPassword) {
        return DigestUtils.md5DigestAsHex((passwordSalt + rawPassword).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public List<SysUser> listByRoleCodes(Collection<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, SysUser> result = new LinkedHashMap<>();
        for (String roleCode : roleCodes) {
            if (!StringUtils.hasText(roleCode)) {
                continue;
            }
            for (SysUser user : listByRoleCode(roleCode)) {
                result.putIfAbsent(user.getId(), user);
            }
        }
        return new ArrayList<>(result.values());
    }

    @Override
    public List<SysUser> listByRoleCode(String roleCode) {
        if (!StringUtils.hasText(roleCode)) {
            return new ArrayList<>();
        }
        return list(new QueryWrapper<SysUser>()
                .like("role_codes", "," + roleCode.trim() + ",")
                .eq("status", 1));
    }

    @Override
    public boolean isLeader(String userId) {
        if (!StringUtils.hasText(userId)) {
            return false;
        }
        return count(new QueryWrapper<SysUser>().eq("leader_id", userId)) > 0;
    }

    /** 统一成 ,a,b, 形式，便于 SQL LIKE 匹配。 */
    private String normalizeCsv(String csv) {
        if (!StringUtils.hasText(csv)) {
            return "";
        }
        List<String> roles = new ArrayList<>();
        for (String role : csv.split(",")) {
            String trimmed = role.trim();
            if (!trimmed.isEmpty() && !roles.contains(trimmed)) {
                roles.add(trimmed);
            }
        }
        if (roles.isEmpty()) {
            return "";
        }
        return roles.stream().collect(Collectors.joining(",", ",", ","));
    }
}
