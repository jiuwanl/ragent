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

import cn.hutool.crypto.digest.BCrypt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 密码哈希工具
 * <p>
 * 新写入一律 BCrypt；历史明文库存量保持可登录（常数时间比较），登录成功后由调用方透明升级
 */
public final class PasswordHasher {

    /**
     * BCrypt 标准产物 60 字符；hutool checkpw 内部按 {@code length - 31} 取子串，
     * 短于该长度的 $2 开头串会越界，因此先按前缀 + 最小长度门控
     */
    private static final int BCRYPT_MIN_LENGTH = 53;

    private static final String BCRYPT_PREFIX = "$2";

    private PasswordHasher() {
    }

    /**
     * 生成 BCrypt 哈希（随机盐）
     */
    public static String encode(String rawPassword) {
        return BCrypt.hashpw(rawPassword, BCrypt.gensalt());
    }

    /**
     * 校验密码：存储值为 BCrypt 走哈希比对，否则按遗留明文常数时间比较
     */
    public static boolean matches(String input, String stored) {
        if (stored == null) {
            return input == null;
        }
        if (input == null) {
            return false;
        }
        if (isBcrypt(stored)) {
            return BCrypt.checkpw(input, stored);
        }
        return MessageDigest.isEqual(
                stored.getBytes(StandardCharsets.UTF_8),
                input.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 是否为需要升级的遗留存储（非空且非 BCrypt）
     */
    public static boolean needsUpgrade(String stored) {
        return stored != null && !isBcrypt(stored);
    }

    private static boolean isBcrypt(String stored) {
        return stored.startsWith(BCRYPT_PREFIX) && stored.length() >= BCRYPT_MIN_LENGTH;
    }
}
