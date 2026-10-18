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

import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.user.controller.request.ChangePasswordRequest;
import com.nageoffer.ai.ragent.user.controller.request.UserCreateRequest;
import com.nageoffer.ai.ragent.user.dao.entity.UserDO;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import com.nageoffer.ai.ragent.user.support.PasswordHasher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private BizChangeLogContext bizChangeLogContext;

    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new UserServiceImpl(userMapper, bizChangeLogContext);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void createStoresBcryptHashInsteadOfRawPassword() {
        when(userMapper.insert(any(UserDO.class))).thenReturn(1);
        UserCreateRequest request = new UserCreateRequest();
        request.setUsername("alice");
        request.setPassword("secret123");
        request.setRole("user");

        service.create(request);

        ArgumentCaptor<UserDO> captor = ArgumentCaptor.forClass(UserDO.class);
        verify(userMapper).insert(captor.capture());
        String stored = captor.getValue().getPassword();
        assertTrue(stored.startsWith("$2"));
        assertTrue(PasswordHasher.matches("secret123", stored));
        assertNotEquals("secret123", stored);
    }

    @Test
    void createRejectsShortPasswordBeforeTouchingDatabase() {
        UserCreateRequest request = new UserCreateRequest();
        request.setUsername("alice");
        request.setPassword("12345");

        assertThrows(ClientException.class, () -> service.create(request));
        verify(userMapper, never()).insert(any(UserDO.class));
    }

    @Test
    void createRejectsOverlongPassword() {
        UserCreateRequest request = new UserCreateRequest();
        request.setUsername("alice");
        request.setPassword("a".repeat(65));

        assertThrows(ClientException.class, () -> service.create(request));
        verify(userMapper, never()).insert(any(UserDO.class));
    }

    @Test
    void changePasswordUpgradesLegacyPlaintextAndStoresHash() {
        UserContext.set(LoginUser.builder().userId("1001").username("bob").role("user").build());
        UserDO existing = UserDO.builder()
                .id("1001")
                .username("bob")
                .password("legacy-plain")
                .role("user")
                .deleted(0)
                .build();
        when(userMapper.selectOne(any())).thenReturn(existing);
        when(userMapper.selectById("1001")).thenReturn(existing);
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setCurrentPassword("legacy-plain");
        request.setNewPassword("new-pass-1");

        service.changePassword(request);

        ArgumentCaptor<UserDO> captor = ArgumentCaptor.forClass(UserDO.class);
        verify(userMapper).updateById(captor.capture());
        String stored = captor.getValue().getPassword();
        assertTrue(stored.startsWith("$2"));
        assertTrue(PasswordHasher.matches("new-pass-1", stored));
        assertNotEquals("new-pass-1", stored);
    }

    @Test
    void changePasswordRejectsWrongCurrentPassword() {
        UserContext.set(LoginUser.builder().userId("1001").username("bob").role("user").build());
        UserDO existing = UserDO.builder()
                .id("1001")
                .username("bob")
                .password("legacy-plain")
                .role("user")
                .deleted(0)
                .build();
        when(userMapper.selectOne(any())).thenReturn(existing);
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setCurrentPassword("not-the-one");
        request.setNewPassword("new-pass-1");

        assertThrows(ClientException.class, () -> service.changePassword(request));
        verify(userMapper, never()).updateById(any(UserDO.class));
    }
}
