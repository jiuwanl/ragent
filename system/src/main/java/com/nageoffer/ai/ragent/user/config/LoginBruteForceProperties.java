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

package com.nageoffer.ai.ragent.user.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 登录防爆破配置
 * <p>
 * 双维度计数：用户名+IP 维度防针对单账号的密码爆破，纯 IP 维度防撞库扫号；
 * enabled 是逃生阀——线上 Redis 异常等场景可一键短路，避免登录面被防爆破自身拖死
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ragent.auth.brute-force")
public class LoginBruteForceProperties {

    /**
     * 总开关，false 时所有检查与计数短路
     */
    private boolean enabled = true;

    /**
     * 失败计数窗口（秒），窗口内计数达到阈值触发锁定
     */
    private long windowSeconds = 300;

    /**
     * 锁定时长（秒），锁定期间凭据正确也拒绝
     */
    private long lockSeconds = 900;

    /**
     * 同一用户名+IP 在窗口内的失败上限，达到即锁该用户名（所有来源）
     */
    private long maxFailuresPerUserIp = 5;

    /**
     * 同一 IP 在窗口内的失败上限（跨用户名累计），达到即锁该 IP，防单 IP 扫号
     */
    private long maxFailuresPerIp = 20;
}
