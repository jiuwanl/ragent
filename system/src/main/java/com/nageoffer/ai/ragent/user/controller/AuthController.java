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

package com.nageoffer.ai.ragent.user.controller;

import com.nageoffer.ai.ragent.user.controller.request.LoginRequest;
import com.nageoffer.ai.ragent.user.controller.request.RegisterRequest;
import com.nageoffer.ai.ragent.user.controller.vo.LoginVO;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.user.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证控制器
 * 处理用户登录和登出相关的请求
 */
@Tag(name = "认证", description = "登录、注册与会话；令牌放 Authorization 头，不带 Bearer 前缀")
@RestController
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 用户登录接口
     */
    @Operation(summary = "登录", description = "用户名或密码错误返回统一文案；连续失败触发防爆破锁定，锁定期间凭据正确也拒绝")
    @PostMapping("/auth/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginRequest requestParam) {
        return Results.success(authService.login(requestParam));
    }

    /**
     * 用户自助注册接口，注册成功后自动登录
     */
    @Operation(summary = "自助注册", description = "注册即登录，角色固定普通用户；密码 BCrypt 存储")
    @PostMapping("/auth/register")
    public Result<LoginVO> register(@Valid @RequestBody RegisterRequest requestParam) {
        return Results.success(authService.register(requestParam));
    }

    /**
     * 用户登出接口，清除用户的认证信息和会话
     */
    @PostMapping("/auth/logout")
    public Result<Void> logout() {
        authService.logout();
        return Results.success();
    }
}
