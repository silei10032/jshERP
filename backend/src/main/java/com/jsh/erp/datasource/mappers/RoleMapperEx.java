package com.jsh.erp.datasource.mappers;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.jsh.erp.datasource.entities.Role;
import com.jsh.erp.datasource.entities.RoleEx;
import com.jsh.erp.datasource.entities.RoleExample;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

public interface RoleMapperEx {

    List<RoleEx> selectByConditionRole(
            @Param("name") String name,
            @Param("description") String description);

    int batchDeleteRoleByIds(@Param("updateTime") Date updateTime, @Param("updater") Long updater, @Param("ids") String ids[]);

    @InterceptorIgnore(tenantLine = "true")
    Role getRoleWithoutTenant(
            @Param("roleId") Long roleId);
}