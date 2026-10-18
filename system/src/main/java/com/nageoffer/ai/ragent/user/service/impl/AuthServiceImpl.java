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

package com.nageoffer.ai.ragent.user.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.user.controller.request.LoginRequest;
import com.nageoffer.ai.ragent.user.controller.request.RegisterRequest;
import com.nageoffer.ai.ragent.user.controller.request.UserCreateRequest;
import com.nageoffer.ai.ragent.user.controller.vo.LoginVO;
import com.nageoffer.ai.ragent.user.dao.entity.UserDO;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import com.nageoffer.ai.ragent.user.enums.UserRole;
import com.nageoffer.ai.ragent.user.support.LoginBruteForceGuard;
import com.nageoffer.ai.ragent.user.support.PasswordHasher;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.user.service.AuthService;
import com.nageoffer.ai.ragent.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final String DEFAULT_AVATAR_URL = "https://avatars.githubusercontent.com/u/583231?v=4";

    private final UserMapper userMapper;
    private final UserService userService;
    private final LoginBruteForceGuard loginBruteForceGuard;

    @Override
    public LoginVO login(LoginRequest requestParam) {
        String username = requestParam.getUsername();
        String password = requestParam.getPassword();
        if (StrUtil.isBlank(username) || StrUtil.isBlank(password)) {
            throw new ClientException("用户名或密码不能为空");
        }
        // 锁定期间凭据正确也拒绝，检查必须在密码比对之前
        loginBruteForceGuard.checkLoginAllowed(username);
        UserDO user = findByUsername(username);
        if (user == null || !PasswordHasher.matches(password, user.getPassword())) {
            // 用户名不存在与密码错误走同一分支记账：计数对幽灵账号同样累计，枚举不出账号是否存在
            loginBruteForceGuard.recordLoginFailure(username);
            throw new ClientException("用户名或密码错误");
        }
        if (user.getId() == null) {
            throw new ClientException("用户信息异常");
        }
        loginBruteForceGuard.recordLoginSuccess(username);
        String loginId = user.getId().toString();
        StpUtil.login(loginId);
        upgradeLegacyPassword(user, password);
        String avatar = StrUtil.isBlank(user.getAvatar()) ? DEFAULT_AVATAR_URL : user.getAvatar();
        return new LoginVO(loginId, user.getRole(), StpUtil.getTokenValue(), avatar);
    }

    @Override
    public LoginVO register(RegisterRequest requestParam) {
        if (requestParam == null) {
            throw new ClientException("用户名或密码不能为空");
        }
        String username = StrUtil.trimToNull(requestParam.getUsername());
        String password = StrUtil.trimToNull(requestParam.getPassword());
        if (StrUtil.isBlank(username) || StrUtil.isBlank(password)) {
            throw new ClientException("用户名或密码不能为空");
        }
        // 只查 IP 锁：用户名维度的锁不该连坐到注册别的用户名
        loginBruteForceGuard.checkRegisterAllowed();
        UserCreateRequest createParam = new UserCreateRequest();
        createParam.setUsername(username);
        createParam.setPassword(password);
        // 注册入口固定普通用户，客户端无法指定角色
        createParam.setRole(UserRole.USER.getCode());
        try {
            userService.create(createParam);
        } catch (Exception ex) {
            // 用户名已存在等失败也记 IP 账，防对注册面做批量探测
            loginBruteForceGuard.recordRegisterFailure();
            throw ex;
        }
        // 注册即登录：复用登录链路完成凭证校验与会话签发
        LoginRequest loginParam = new LoginRequest();
        loginParam.setUsername(username);
        loginParam.setPassword(password);
        return login(loginParam);
    }

    @Override
    public void logout() {
        StpUtil.logout();
    }

    /**
     * 历史明文密码在登录成功后透明升级为 BCrypt；升级失败不阻断登录，下次登录继续尝试
     */
    private void upgradeLegacyPassword(UserDO user, String rawPassword) {
        if (!PasswordHasher.needsUpgrade(user.getPassword())) {
            return;
        }
        try {
            user.setPassword(PasswordHasher.encode(rawPassword));
            userMapper.updateById(user);
        } catch (Exception ex) {
            // 升级是尽力而为，失败仅保留遗留存储
        }
    }

    private UserDO findByUsername(String username) {
        if (StrUtil.isBlank(username)) {
            return null;
        }
        return userMapper.selectOne(
                Wrappers.lambdaQuery(UserDO.class)
                        .eq(UserDO::getUsername, username)
                        .eq(UserDO::getDeleted, 0)
        );
    }
}
