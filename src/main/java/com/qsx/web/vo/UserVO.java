package com.qsx.web.vo;

import com.qsx.domain.entity.User;
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
}