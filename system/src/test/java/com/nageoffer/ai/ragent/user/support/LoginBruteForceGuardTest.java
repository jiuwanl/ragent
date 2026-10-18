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

package com.nageoffer.ai.ragent.user.support;

import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.user.config.LoginBruteForceProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 防爆破闸门纯逻辑单测（默认层，无 Redis）：
 * 阈值落锁、锁文案统一、成功清计数、enabled 短路。
 * 跨 key 竞态与 TTL 语义在 {@link LoginBruteForceGuardRealRedisTest} 用真实 Redis 验证
 */
@DisplayName("登录防爆破闸门（纯逻辑）")
class LoginBruteForceGuardTest {

    private static final String USERNAME = "victim";
    /** 无请求上下文时 resolveIp 兜底为 unknown，下列 Key 即 guard 的拼接口径 */
    private static final String FAIL_USER_KEY = "auth:login:fail:u:victim:unknown";
    private static final String FAIL_IP_KEY = "auth:login:fail:ip:unknown";
    private static final String LOCK_USER_KEY = "auth:login:lock:u:victim";
    private static final String LOCK_IP_KEY = "auth:login:lock:ip:unknown";

    private RedissonClient redissonClient;
    private RBucket<String> userLockBucket;
    private RBucket<String> ipLockBucket;
    private RScript script;
    private RKeys keys;
    private final Map<String, Long> counters = new HashMap<>();
    private LoginBruteForceProperties properties;
    private LoginBruteForceGuard guard;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redissonClient = mock(RedissonClient.class);
        userLockBucket = mock(RBucket.class);
        ipLockBucket = mock(RBucket.class);
        script = mock(RScript.class);
        keys = mock(RKeys.class);
        properties = new LoginBruteForceProperties();

        // getBucket 泛型按调用点推断成 RBucket<Object>，thenReturn 会被拒；doReturn 不做编译期类型检查
        doReturn(userLockBucket).when(redissonClient).getBucket(eq(LOCK_USER_KEY), any(Codec.class));
        doReturn(ipLockBucket).when(redissonClient).getBucket(eq(LOCK_IP_KEY), any(Codec.class));
        // getScript 有 Codec / OptionalOptions 两个重载，裸 any() 无法消歧
        when(redissonClient.getScript(any(Codec.class))).thenReturn(script);
        when(redissonClient.getKeys()).thenReturn(keys);
        // 计数按 Key 累加回放：eval 的 KEYS[1] 即计数键，返回当前累计值
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                .thenAnswer(invocation -> {
                    String key = ((List<String>) invocation.getArgument(3)).get(0);
                    return counters.getOrDefault(key, 0L);
                });

        guard = new LoginBruteForceGuard(redissonClient, properties);
    }

    @Test
    @DisplayName("用户维度锁定后拒绝，文案为统一锁定文案")
    void shouldRejectWhenUserLockExists() {
        when(userLockBucket.isExists()).thenReturn(true);

        assertThatThrownBy(() -> guard.checkLoginAllowed(USERNAME))
                .isInstanceOf(ClientException.class)
                .hasMessage(LoginBruteForceGuard.LOCK_MESSAGE);
    }

    @Test
    @DisplayName("IP 维度锁定与用户维度锁定返回同一文案，锁因不外漏")
    void shouldRejectWithSameMessageWhenIpLockExists() {
        when(ipLockBucket.isExists()).thenReturn(true);

        assertThatThrownBy(() -> guard.checkLoginAllowed(USERNAME))
                .isInstanceOf(ClientException.class)
                .hasMessage(LoginBruteForceGuard.LOCK_MESSAGE);
    }

    @Test
    @DisplayName("失败达用户维度阈值时锁该用户名，IP 计数未达阈值不锁 IP")
    void shouldLockUserWhenUserIpThresholdReached() {
        counters.put(FAIL_USER_KEY, 5L);
        counters.put(FAIL_IP_KEY, 3L);

        guard.recordLoginFailure(USERNAME);

        verify(userLockBucket).set(eq("1"), eq(Duration.ofSeconds(properties.getLockSeconds())));
        verify(ipLockBucket, never()).set(any(), any(Duration.class));
    }

    @Test
    @DisplayName("IP 计数达全局阈值时锁整个 IP，锁的是 IP 而非用户名")
    void shouldLockIpWhenGlobalThresholdReached() {
        counters.put(FAIL_USER_KEY, 1L);
        counters.put(FAIL_IP_KEY, 20L);

        guard.recordLoginFailure(USERNAME);

        verify(ipLockBucket).set(eq("1"), eq(Duration.ofSeconds(properties.getLockSeconds())));
        verify(userLockBucket, never()).set(any(), any(Duration.class));
    }

    @Test
    @DisplayName("两个维度都低于阈值时不落任何锁")
    void shouldNotLockBelowThresholds() {
        counters.put(FAIL_USER_KEY, 4L);
        counters.put(FAIL_IP_KEY, 19L);

        guard.recordLoginFailure(USERNAME);

        verify(userLockBucket, never()).set(any(), any(Duration.class));
        verify(ipLockBucket, never()).set(any(), any(Duration.class));
    }

    @Test
    @DisplayName("登录成功只清用户名+IP 计数，IP 全局计数保留给窗口自然过期")
    void shouldClearOnlyUserScopedCounterOnSuccess() {
        guard.recordLoginSuccess(USERNAME);

        verify(keys).delete(FAIL_USER_KEY);
        verify(keys, never()).delete(FAIL_IP_KEY);
    }

    @Test
    @DisplayName("enabled=false 时检查与记账全部短路，Redis 零交互")
    void shouldNoOpWhenDisabled() {
        properties.setEnabled(false);
        when(userLockBucket.isExists()).thenReturn(true);

        guard.checkLoginAllowed(USERNAME);
        guard.recordLoginFailure(USERNAME);
        guard.recordLoginSuccess(USERNAME);
        guard.checkRegisterAllowed();
        guard.recordRegisterFailure();

        verifyNoInteractions(redissonClient);
    }

    @Test
    @DisplayName("注册只看 IP 锁：用户名锁不阻断注册别的用户名")
    void shouldBlockRegisterOnlyOnIpLock() {
        when(userLockBucket.isExists()).thenReturn(true);

        guard.checkRegisterAllowed();

        verify(userLockBucket, never()).isExists();
        verify(ipLockBucket).isExists();
    }
}
