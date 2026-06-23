package com.jsh.erp.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.jsh.erp.utils.Tools;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.mybatis.spring.mapper.MapperScannerConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;

/**
 * 多租户 + 分页拦截器配置（MyBatis-Plus 3.5 新 API）。
 *
 * 原 MP 3.0 时代使用 PaginationInterceptor + TenantSqlParser + ISqlParserFilter，
 * 3.5 改为 MybatisPlusInterceptor + TenantLineInnerInterceptor + 在 mapper 方法上
 * 加 @InterceptorIgnore(tenantLine="true") 跳过个别查询。
 */
@Service
public class TenantConfig {

    /** 全租户共享的表名白名单，对这些表不注入 tenant_id 过滤 */
    private static final Set<String> TENANT_IGNORE_TABLES = Set.of(
            "jsh_sequence", "jsh_function", "jsh_platform_config",
            "jsh_tenant", "jsh_sys_dict_data", "jsh_sys_dict_type"
    );

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(HttpServletRequest request) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 多租户拦截器必须放在分页之前
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                String token = request.getHeader("X-Access-Token");
                Long tenantId = Tools.getTenantIdByToken(token);
                return new LongValue(tenantId);
            }

            @Override
            public String getTenantIdColumn() {
                return "tenant_id";
            }

            @Override
            public boolean ignoreTable(String tableName) {
                String token = request.getHeader("X-Access-Token");
                Long tenantId = Tools.getTenantIdByToken(token);
                // 超管 tenantId == 0：所有表跳过 tenant 过滤
                if (tenantId == 0L) {
                    return true;
                }
                // 普通租户：共享表白名单跳过
                return TENANT_IGNORE_TABLES.contains(tableName);
            }
        }));
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    @Bean
    public MapperScannerConfigurer mapperScannerConfigurer() {
        MapperScannerConfigurer scannerConfigurer = new MapperScannerConfigurer();
        scannerConfigurer.setBasePackage("com.jsh.erp.datasource.mappers*");
        return scannerConfigurer;
    }
}
