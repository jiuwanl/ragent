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
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RedissonClient;
import org.redisson.api.RScript;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.util.List;

/**
 * 登录防爆破闸门
 * <p>
 * 失败计数与锁都落 Redis：多实例部署下计数天然共享，锁定对全体实例即时生效。
 * 计数用 Lua 原子 INCR+EXPIRE——分开调用时进程在两步之间崩溃会留下永不过期的计数键，
 * 阈值从此永久打满，一次网络抖动变成永久锁号
 * <p>
 * 维度与 Key：
 * <pre>
 * auth:login:fail:u:{username}:{ip}   窗口内失败计数（用户名+IP）
 * auth:login:fail:ip:{ip}             窗口内失败计数（IP 跨用户名，防扫号）
 * auth:login:lock:u:{username}        用户名锁定（存在即拒，凭据正确也拒）
 * auth:login:lock:ip:{ip}             IP 锁定
 * </pre>
 * 锁定文案不区分用户名是否存在，也不区分锁因——计数对不存在的用户名同样累计，
 * 枚举账号的存在性不会从响应差异里漏出去
 */
@Component
@RequiredArgsConstructor
public class LoginBruteForceGuard {

    /**
     * 锁定期间统一文案：不透露锁因与剩余时长，防账号枚举
     */
    static final String LOCK_MESSAGE = "操作过于频繁，请稍后再试";

    private static final String FAIL_USER_KEY = "auth:login:fail:u:";
    private static final String FAIL_IP_KEY = "auth:login:fail:ip:";
    private static final String LOCK_USER_KEY = "auth:login:lock:u:";
    private static final String LOCK_IP_KEY = "auth:login:lock:ip:";

    /**
     * INCR 后对首个写入者补 EXPIRE：计数键必须随窗口滚动过期，否则历史失败永久累加
     */
    private static final String INCR_WITH_WINDOW =
            "local c = redis.call('INCR', KEYS[1])\n"
            + "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end\n"
            + "return c";

    private static final String UNKNOWN_IP = "unknown";

    private final RedissonClient redissonClient;
    private final LoginBruteForceProperties properties;

    /**
     * 登录前检查：命中任一维度的锁即拒绝
     */
    public void checkLoginAllowed(String username) {
        if (!properties.isEnabled()) {
            return;
        }
        String ip = resolveIp();
        if (isLocked(lockUserKey(username)) || isLocked(lockIpKey(ip))) {
            throw new ClientException(LOCK_MESSAGE);
        }
    }

    /**
     * 注册前检查：只看 IP 维度——用户名维度的锁语义是"这个账号别登录"，
     * 不该连坐到注册别的用户名
     */
    public void checkRegisterAllowed() {
        if (!properties.isEnabled()) {
            return;
        }
        if (isLocked(lockIpKey(resolveIp()))) {
            throw new ClientException(LOCK_MESSAGE);
        }
    }

    /**
     * 登录失败记账：两个维度各自累加，谁先到阈值就落谁的锁。
     * 用户名锁立刻生效；IP 锁是兜底，防攻击者轮换用户名慢慢扫
     */
    public void recordLoginFailure(String username) {
        if (!properties.isEnabled()) {
            return;
        }
        String ip = resolveIp();
        long userIpFailures = incrementWithWindow(failUserKey(username, ip));
        if (userIpFailures >= properties.getMaxFailuresPerUserIp()) {
            setLock(lockUserKey(username));
        }
        long ipFailures = incrementWithWindow(failIpKey(ip));
        if (ipFailures >= properties.getMaxFailuresPerIp()) {
            setLock(lockIpKey(ip));
        }
    }

    /**
     * 注册失败记账（用户名已存在等）：只累加 IP 维度，防批量注册探测
     */
    public void recordRegisterFailure() {
        if (!properties.isEnabled()) {
            return;
        }
        String ip = resolveIp();
        if (incrementWithWindow(failIpKey(ip)) >= properties.getMaxFailuresPerIp()) {
            setLock(lockIpKey(ip));
        }
    }

    /**
     * 登录成功清除用户名+IP 维度计数。
     * IP 维度刻意不清：撞库者手里有一个合法账号就能借成功登录洗白计数，防扫号维度交给窗口自然过期
     */
    public void recordLoginSuccess(String username) {
        if (!properties.isEnabled()) {
            return;
        }
        redissonClient.getKeys().delete(failUserKey(username, resolveIp()));
    }

    private boolean isLocked(String lockKey) {
        return redissonClient.getBucket(lockKey, StringCodec.INSTANCE).isExists();
    }

    private void setLock(String lockKey) {
        redissonClient.getBucket(lockKey, StringCodec.INSTANCE)
                .set("1", Duration.ofSeconds(properties.getLockSeconds()));
    }

    private long incrementWithWindow(String counterKey) {
        Object result = redissonClient.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE,
                INCR_WITH_WINDOW,
                RScript.ReturnType.VALUE,
                List.of(counterKey),
                String.valueOf(properties.getWindowSeconds()));
        if (result instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(result));
    }

    private static String failUserKey(String username, String ip) {
        return FAIL_USER_KEY + username + ":" + ip;
    }

    private static String failIpKey(String ip) {
        return FAIL_IP_KEY + ip;
    }

    private static String lockUserKey(String username) {
        return LOCK_USER_KEY + username;
    }

    private static String lockIpKey(String ip) {
        return LOCK_IP_KEY + ip;
    }

    /**
     * 客户端 IP：代理链路优先取 X-Forwarded-For 首跳，与审计日志同一口径；
     * 非请求线程（定时任务等）兜底 unknown，多个无请求来源会共用计数——登录必然来自 HTTP 线程，此场景仅测试可达
     */
    String resolveIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            String forwardedFor = firstHeaderValue(request.getHeader("X-Forwarded-For"));
            if (StringUtils.hasText(forwardedFor)) {
                return forwardedFor;
            }
            String realIp = request.getHeader("X-Real-IP");
            return StringUtils.hasText(realIp) ? realIp : request.getRemoteAddr();
        }
        return UNKNOWN_IP;
    }

    private static String firstHeaderValue(String headerValue) {
        if (!StringUtils.hasText(headerValue)) {
            return null;
        }
        int commaIndex = headerValue.indexOf(',');
        return commaIndex >= 0 ? headerValue.substring(0, commaIndex).trim() : headerValue.trim();
    }
}
