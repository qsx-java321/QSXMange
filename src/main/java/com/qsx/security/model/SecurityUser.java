package com.qsx.security.model;

import com.qsx.domain.entity.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Spring Security 用户主体，微封装持久层实体
 */
public class SecurityUser implements UserDetails {

    private static final String ROLE_PREFIX = "ROLE_";

    @Getter
    private final User user;

    /** 角色码（如 ADMIN），authority 中统一加 ROLE_ 前缀 */
    private final List<String> roles;

    /** 权限码（如 user:add），authority 中原样返回 */
    private final List<String> permissions;

    public SecurityUser(User user) {
        this(user, Collections.emptyList(), Collections.emptyList());
    }

    public SecurityUser(User user, List<String> roles, List<String> permissions) {
        this.user = user;
        this.roles = roles == null ? Collections.emptyList() : roles;
        this.permissions = permissions == null ? Collections.emptyList() : permissions;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return Stream.concat(
                roles.stream().map(code -> new SimpleGrantedAuthority(ROLE_PREFIX + code)),
                permissions.stream().map(SimpleGrantedAuthority::new))
                .collect(Collectors.toList());
    }

    public List<String> getRoles() {
        return roles;
    }

    public List<String> getPermissions() {
        return permissions;
    }

    @Override
    public String getPassword() {
        return user.getPassword();
    }

    @Override
    public String getUsername() {
        return user.getEmail();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        // status: 0-正常，1-禁用
        return user.getStatus() != null && user.getStatus() == 0;
    }
}