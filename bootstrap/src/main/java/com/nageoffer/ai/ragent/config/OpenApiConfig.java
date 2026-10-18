/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 元信息
 * <p>
 * 鉴权方案刻意用 apiKey 直塞 Header 而非 HTTP Bearer：本项目 Sa-Token 的 token-name
 * 就是 Authorization 且不带 Bearer 前缀（见 docs/LOCAL-USAGE.md §6），
 * 填 Bearer 会让 Swagger UI Try-it-out 送出带前缀的凭证被服务端拒收
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI ragentOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Ragent API")
                        .description("Agentic RAG 知识库平台：认证、知识库管理、检索问答与 Agent 执行。"
                                + "SSE 流式接口请用 curl 或 EventSource 消费，Swagger UI 仅适合查看契约。")
                        .version("0.0.1-SNAPSHOT"))
                .components(new Components()
                        .addSecuritySchemes("satoken", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("Authorization")
                                .description("Sa-Token 令牌原样放入，不带 Bearer 前缀")))
                // 全局引用：所有端点的 Try-it-out 都会带上 Authorization 头，免去逐端点打注解
                .addSecurityItem(new SecurityRequirement().addList("satoken"));
    }
}
