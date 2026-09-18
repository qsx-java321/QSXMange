package com.qsx.web.vo;

import com.qsx.domain.entity.User;
import com.qsx.security.port.AuthUserAccount;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户视图对象（不暴露密码）
 */
@Data
public class UserVO {

    private Long id;
    private String email;
    private String nickname;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public static UserVO from(User user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setEmail(user.getEmail());
        vo.setNickname(user.getNickname());
        vo.setStatus(user.getStatus());
        vo.setCreateTime(user.getCreateTime());
        vo.setUpdateTime(user.getUpdateTime());
        return vo;
    }

    /**
     * 从认证上下文中的用户快照转换（/auth/me 用）。
     * 字段与 {@link #from(User)} 完全一致，保证同一用户两条路径返回同一结构。
     */
    public static UserVO from(AuthUserAccount account) {
        UserVO vo = new UserVO();
        vo.setId(account.id());
        vo.setEmail(account.email());
        vo.setNickname(account.nickname());
        vo.setStatus(account.status());
        vo.setCreateTime(account.createTime());
        vo.setUpdateTime(account.updateTime());
        return vo;
    }
}