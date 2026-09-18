package com.qsx.security.model;

import com.qsx.security.port.AuthUserAccount;
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
 * Spring Security 用户主体，持有认证所需的用户行快照（{@link AuthUserAccount}）
 *
 * <p>刻意不持有业务实体 User：那会让 security 模块反向依赖业务模块，
 * 使认证内核无法被单独复用。
 */
public class SecurityUser implements UserDetails {

    private static final String ROLE_PREFIX = "ROLE_";

    @Getter
    private final AuthUserAccount account;

    /** 角色码（如 ADMIN），authority 中统一加 ROLE_ 前缀 */
    private final List<String> roles;

    /** 权限码（如 user:add），authority 中原样返回 */
    private final List<String> permissions;

    public SecurityUser(AuthUserAccount account, List<String> roles, List<String> permissions) {
        this.account = account;
        this.roles = roles == null ? Collections.emptyList() : roles;
        this.permissions = permissions == null ? Collections.emptyList() : permissions;
    }

    public Long getId() {
        return account.id();
    }

    public String getEmail() {
        return account.email();
    }

    public String getNickname() {
        return account.nickname();
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
        return account.password();
    }

    @Override
    public String getUsername() {
        return account.email();
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
        return account.enabled();
    }
}