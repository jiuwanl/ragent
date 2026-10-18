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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {

    @Test
    void encodeProducesStandardBcryptHash() {
        String hash = PasswordHasher.encode("secret123");

        assertTrue(hash.startsWith("$2"));
        assertEquals(60, hash.length());
        assertNotEquals("secret123", hash);
    }

    @Test
    void matchesAcceptsCorrectAndRejectsWrongPasswordAgainstBcrypt() {
        String hash = PasswordHasher.encode("secret123");

        assertTrue(PasswordHasher.matches("secret123", hash));
        assertFalse(PasswordHasher.matches("wrong-pass", hash));
    }

    @Test
    void matchesSupportsLegacyPlaintextStorage() {
        assertTrue(PasswordHasher.matches("admin", "admin"));
        assertFalse(PasswordHasher.matches("admin", "admin2"));
    }

    @Test
    void matchesPreservesNullSemantics() {
        assertTrue(PasswordHasher.matches(null, null));
        assertFalse(PasswordHasher.matches("secret123", null));
        assertFalse(PasswordHasher.matches(null, PasswordHasher.encode("secret123")));
        assertFalse(PasswordHasher.matches(null, "admin"));
    }

    @Test
    void needsUpgradeFlagsOnlyLegacyStorage() {
        assertTrue(PasswordHasher.needsUpgrade("admin"));
        assertTrue(PasswordHasher.needsUpgrade(""));
        assertFalse(PasswordHasher.needsUpgrade(null));
        assertFalse(PasswordHasher.needsUpgrade(PasswordHasher.encode("secret123")));
    }

    @Test
    void malformedBcryptPrefixedStorageReturnsFalseWithoutThrowing() {
        assertFalse(PasswordHasher.matches("secret123", "$2"));
        assertFalse(PasswordHasher.matches("secret123", "$2a$10$too-short"));
        assertFalse(PasswordHasher.matches("secret123", "$2x$10$abcdefghijklmnopqrstuvwxy"));
    }
}
