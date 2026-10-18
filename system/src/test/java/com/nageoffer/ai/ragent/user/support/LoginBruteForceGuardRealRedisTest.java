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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;

import java.net.URI;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 防爆破闸门真实 Redis 集成测试：阈值落锁、成功清计数、锁 TTL 恢复、IP 维度跨用户名生效
 * <p>
 * 锁语义建立在 Lua 原子计数与 Redis TTL 之上，mock 复现不了 EXPIRE 滚动与真实过期；
 * 默认分层排除（@Tag("integration")），本地起 Redis 后用
 * {@code -Dgroups=integration -DexcludedGroups=live} 显式执行。
 * 每个用例以唯一用户名+唯一 IP 构造闸门（覆写 resolveIp），键空间互不重叠且全部带 TTL 自清
 */
@Tag("integration")
@DisplayName("登录防爆破闸门（真实 Redis）")
class LoginBruteForceGuardRealRedisTest {

    private static final String DEFAULT_REDIS_URL = "redis://:123456@127.0.0.1:6379";
    private static final int USER_IP_FAILURES = 5;

    private RedissonClient redisson;
    private String username;
    private String ip;
    private LoginBruteForceProperties properties;
    private LoginBruteForceGuard guard;

    @BeforeEach
    void connectRedis() {
        username = "user-" + UUID.randomUUID();
        ip = "ip-" + UUID.randomUUID();
        String url = System.getenv().getOrDefault("TEST_REDIS_URL", DEFAULT_REDIS_URL);
        try {
            redisson = Redisson.create(redisConfig(url));
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Redis 不可达，integration 层需先起本地 Redis（或注入 TEST_REDIS_URL），当前地址：" + url, ex);
        }
        properties = new LoginBruteForceProperties();
        guard = guardWithUniqueIp(properties);
    }

    @AfterEach
    void cleanUp() {
        if (redisson != null) {
            // 三个键以 :{ip} 结尾可按模式清，用户名锁单独删；正常路径下它们本就带 TTL
            redisson.getKeys().deleteByPattern("auth:login:*:" + ip);
            redisson.getKeys().delete("auth:login:lock:u:" + username);
            redisson.shutdown();
        }
    }

    @Test
    @DisplayName("失败达阈值锁用户名，锁定期间检查即拒")
    void shouldLockUserAfterThresholdFailuresAndRejectWhileLocked() {
        for (int i = 0; i < USER_IP_FAILURES; i++) {
            guard.recordLoginFailure(username);
        }

        assertThat(userLockBucket().isExists()).as("第 5 次失败后应落下用户名锁").isTrue();
        assertThatThrownBy(() -> guard.checkLoginAllowed(username))
                .isInstanceOf(ClientException.class)
                .hasMessage(LoginBruteForceGuard.LOCK_MESSAGE);
    }

    @Test
    @DisplayName("成功清计数后，后续失败从零起算")
    void shouldClearUserCounterOnSuccessSoLaterFailuresStartFresh() {
        for (int i = 0; i < USER_IP_FAILURES - 1; i++) {
            guard.recordLoginFailure(username);
        }
        guard.recordLoginSuccess(username);
        for (int i = 0; i < USER_IP_FAILURES - 1; i++) {
            guard.recordLoginFailure(username);
        }

        assertThat(userLockBucket().isExists())
                .as("成功已清零，两段各 4 次失败都不该独立触发第 5 次落锁").isFalse();
        guard.checkLoginAllowed(username);
    }

    @Test
    @DisplayName("锁定到期后自动恢复，正确密码语义上可再进")
    void shouldRecoverAfterLockTtlExpires() throws InterruptedException {
        properties.setLockSeconds(2);
        for (int i = 0; i < USER_IP_FAILURES; i++) {
            guard.recordLoginFailure(username);
        }
        assertThatThrownBy(() -> guard.checkLoginAllowed(username))
                .isInstanceOf(ClientException.class);

        // 等锁键真实过期，而非直接删键——验的就是 TTL 这条恢复路径
        Thread.sleep(2_500);

        assertThat(userLockBucket().isExists()).as("TTL 到期锁键应消失").isFalse();
        guard.checkLoginAllowed(username);
    }

    @Test
    @DisplayName("IP 维度落锁后同 IP 的其他用户名也被拒")
    void shouldLockIpAcrossUsernames() {
        properties.setMaxFailuresPerIp(3);
        for (int i = 0; i < 3; i++) {
            guard.recordLoginFailure(username);
        }

        String otherUsername = "other-" + UUID.randomUUID();
        assertThatThrownBy(() -> guard.checkLoginAllowed(otherUsername))
                .as("IP 被锁后，没失败过的用户名也应连带拒绝")
                .isInstanceOf(ClientException.class)
                .hasMessage(LoginBruteForceGuard.LOCK_MESSAGE);
    }

    @Test
    @DisplayName("enabled=false 时狂刷失败也不锁不拒")
    void shouldNoOpWhenDisabled() {
        properties.setEnabled(false);
        for (int i = 0; i < USER_IP_FAILURES * 2; i++) {
            guard.recordLoginFailure(username);
        }

        guard.checkLoginAllowed(username);
        assertThat(userLockBucket().isExists()).as("短路开关下不应有任何锁键").isFalse();
    }

    // ==================== 工具 ====================

    /**
     * 覆写 IP 解析注入唯一 IP：测试无 HTTP 上下文，兜底 unknown 会让所有用例共享同一把 IP 键
     */
    private LoginBruteForceGuard guardWithUniqueIp(LoginBruteForceProperties props) {
        return new LoginBruteForceGuard(redisson, props) {
            @Override
            String resolveIp() {
                return ip;
            }
        };
    }

    private RBucket<String> userLockBucket() {
        return redisson.getBucket("auth:login:lock:u:" + username, StringCodec.INSTANCE);
    }

    private static Config redisConfig(String url) {
        URI uri = URI.create(url);
        Config config = new Config();
        SingleServerConfig single = config.useSingleServer()
                .setAddress("redis://" + uri.getHost() + ":" + uri.getPort())
                .setConnectionMinimumIdleSize(2)
                .setConnectionPoolSize(8);
        String userInfo = uri.getUserInfo();
        if (userInfo != null && userInfo.contains(":")) {
            single.setPassword(userInfo.substring(userInfo.indexOf(':') + 1));
        }
        return config;
    }
}
