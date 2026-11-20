package com.serviceflow.ai.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 元信息：标题、版本、JWT Bearer 鉴权方式与统一错误码说明。
 * 接口列表由 springdoc 扫描 Controller 自动生成，地址见 docs/phase7-api-documentation.md。
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearerAuth";

    @Bean
    public OpenAPI serviceFlowOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("ServiceFlow AI API")
                        .version("0.1.0")
                        .description("""
                                智能工单与服务调度平台后端接口。
                                统一返回结构：{ code, message, data, timestamp }。
                                鉴权：除 /api/auth/login、/api/health/** 外，均需请求头 Authorization: Bearer <token>。
                                角色：ADMIN（全部）、AGENT（处理与调度）、USER（自己创建的工单）。
                                时间格式：yyyy-MM-dd HH:mm:ss（Asia/Shanghai）。
                                分页：page 从 1 开始，pageSize 默认 10、上限 100。
                                """)
                        .contact(new Contact().name("ServiceFlow AI")))
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("登录后返回的访问令牌")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
