package com.qsx.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qsx.common.exception.BusinessException;
import com.qsx.common.result.PageResult;
import com.qsx.common.result.ResultCode;
import com.qsx.domain.entity.Permission;
import com.qsx.mapper.PermissionMapper;
import com.qsx.service.PermissionService;
import com.qsx.web.dto.query.PermissionQuery;
import com.qsx.web.vo.PermissionVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 权限管理服务实现（只读）
 */
@Service
public class PermissionServiceImpl implements PermissionService {

    private final PermissionMapper permissionMapper;

    public PermissionServiceImpl(PermissionMapper permissionMapper) {
        this.permissionMapper = permissionMapper;
    }

    @Override
    public PageResult<PermissionVO> page(PermissionQuery query) {
        LambdaQueryWrapper<Permission> wrapper = new LambdaQueryWrapper<Permission>()
                .like(StringUtils.hasText(query.getCode()), Permission::getCode, query.getCode())
                .like(StringUtils.hasText(query.getName()), Permission::getName, query.getName())
                .orderByAsc(Permission::getSort);

        Page<Permission> page = permissionMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        return PageResult.of(page.convert(PermissionVO::from));
    }

    @Override
    public PermissionVO getById(Long id) {
        Permission permission = permissionMapper.selectById(id);
        if (permission == null) {
            throw new BusinessException(ResultCode.PERMISSION_NOT_FOUND);
        }
        return PermissionVO.from(permission);
    }

    @Override
    public List<PermissionVO> listAll() {
        return permissionMapper.selectList(
                        new LambdaQueryWrapper<Permission>().orderByAsc(Permission::getSort))
                .stream().map(PermissionVO::from).collect(Collectors.toList());
    }
}