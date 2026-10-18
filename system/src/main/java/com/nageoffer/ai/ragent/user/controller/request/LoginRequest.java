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

package com.nageoffer.ai.ragent.user.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LoginRequest {

    /**
     * 只设上限不设下限：登录口对短输入照常走失败记账，长短差异不给枚举账号当侧信道
     */
    @NotBlank(message = "用户名不能为空")
    @Size(max = 64, message = "用户名过长，最多 64 个字符")
    private String username;

    /**
     * 上限 128 宽于注册的 64：历史明文库存量密码可能更长，登录口不能先于比对一步拒掉
     */
    @NotBlank(message = "密码不能为空")
    @Size(max = 128, message = "密码过长，最多 128 个字符")
    private String password;
}
