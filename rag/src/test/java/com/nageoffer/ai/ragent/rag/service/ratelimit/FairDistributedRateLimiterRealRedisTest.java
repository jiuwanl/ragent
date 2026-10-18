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

package com.nageoffer.ai.ragent.rag.service.ratelimit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RBucket;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.core.io.ClassPathResource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * 公平限流器真实 Redis 集成测试：排队次序、并发不超额、queue_claim_atomic.lua 的僵尸清理
 * <p>
 * 排队语义建立在 Lua 原子 claim 与 ZSet score 次序之上，mock Redisson 复现不了跨 key 竞态；
 * 其中僵尸 ZREM 是全仓唯一从未被真实执行过的关键逻辑。默认分层排除（@Tag("integration")），
 * 本地起 Redis 后用 {@code -Dgroups=integration -DexcludedGroups=live} 显式执行；
 * 地址读 TEST_REDIS_URL，缺省对齐本机 compose 密码，CI 注入无密码地址
 */
@Tag("integration")
@DisplayName("分布式公平限流器（真实 Redis）")
class FairDistributedRateLimiterRealRedisTest {

    private static final String DEFAULT_REDIS_URL = "redis://:123456@127.0.0.1:6379";
    private static final String LUA_PATH = "lua/queue_claim_atomic.lua";
    private static final long AWAIT_SECONDS = 15;
    private static final int QUEUE_WAIT_MILLIS = 10_000;

    private RedissonClient redisson;
    private FairDistributedRateLimiter limiter;
    private ExecutorService businessPool;
    private String name;

    @BeforeEach
    void connectRedis() {
        name = "test-fair:" + UUID.randomUUID();
        String url = System.getenv().getOrDefault("TEST_REDIS_URL", DEFAULT_REDIS_URL);
        try {
            redisson = Redisson.create(redisConfig(url));
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Redis 不可达，integration 层需先起本地 Redis（或注入 TEST_REDIS_URL），当前地址：" + url, ex);
        }
        businessPool = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void cleanUp() {
        if (limiter != null) {
            limiter.stop();
            limiter = null;
        }
        if (businessPool != null) {
            businessPool.shutdownNow();
        }
        if (redisson != null) {
            // 每个用例独立 name 前缀，按前缀清掉 semaphore/queue/entry/topic 全套 key
            redisson.getKeys().deleteByPattern(name + ":*");
            redisson.shutdown();
        }
    }

    /**
     * maxPermits=1 时先到先得：先占住 permit 把后 3 张票压进队列，放行后必须按入队次序逐个 grant
     */
    @Test
    @DisplayName("单 permit 下排队票据按 FIFO 顺序放行")
    void shouldGrantQueuedTicketsInFifoOrder() throws Exception {
        limiter = new FairDistributedRateLimiter(name, redisson, () -> 1, () -> 30, () -> 50);
        limiter.start();

        List<String> grantedOrder = Collections.synchronizedList(new ArrayList<>());
        List<String> timedOut = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch firstHolding = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch allGranted = new CountDownLatch(4);

        acquire("t1", grantedOrder, timedOut, allGranted, () -> {
            firstHolding.countDown();
            awaitQuietly(releaseFirst);
        });
        assertThat(firstHolding.await(AWAIT_SECONDS, TimeUnit.SECONDS))
                .as("t1 应立即拿到唯一 permit").isTrue();

        for (int i = 2; i <= 4; i++) {
            acquire("t" + i, grantedOrder, timedOut, allGranted, () -> {
            });
        }
        releaseFirst.countDown();

        assertThat(allGranted.await(AWAIT_SECONDS, TimeUnit.SECONDS))
                .as("4 张票都应放行，实际顺序=%s", grantedOrder).isTrue();
        assertThat(timedOut).isEmpty();
        assertThat(grantedOrder).containsExactly("t1", "t2", "t3", "t4");
    }

    /**
     * 10 张票并发抢 2 个 permit：任何时刻的在场持有者都不能超过 maxPermits
     */
    @Test
    @DisplayName("10 并发下同时持有 permit 数不超过上限")
    void shouldNotExceedPermitsUnderConcurrentAcquire() throws Exception {
        int permits = 2;
        limiter = new FairDistributedRateLimiter(name, redisson, () -> permits, () -> 30, () -> 50);
        limiter.start();

        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        List<String> grantedOrder = Collections.synchronizedList(new ArrayList<>());
        List<String> timedOut = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch allGranted = new CountDownLatch(10);
        CountDownLatch startGun = new CountDownLatch(1);

        ExecutorService submitters = Executors.newFixedThreadPool(10);
        try {
            for (int i = 1; i <= 10; i++) {
                String ticket = "c" + i;
                submitters.execute(() -> {
                    awaitQuietly(startGun);
                    acquire(ticket, grantedOrder, timedOut, allGranted, () -> {
                        int holding = active.incrementAndGet();
                        maxActive.accumulateAndGet(holding, Math::max);
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                        active.decrementAndGet();
                    });
                });
            }
            startGun.countDown();
            assertThat(allGranted.await(30, TimeUnit.SECONDS))
                    .as("10 张票都应放行，已放行=%s", grantedOrder.size()).isTrue();
        } finally {
            submitters.shutdownNow();
        }

        assertThat(timedOut).isEmpty();
        assertThat(maxActive.get())
                .as("同时持有数上限被突破")
                .isLessThanOrEqualTo(permits);
    }

    /**
     * 队头僵尸（entry 标记已消失）必须被 Lua 顺手 ZREM，否则占着 head 窗口把后面的活人全堵死；
     * 僵尸让位后 live-a 虽排在第三位，仍在 liveRank 窗口内拿到 claim
     */
    @Test
    @DisplayName("Lua 清理队头僵尸且存活条目照常 claim")
    void shouldEvictZombieHeadEntriesSoLiveOnesCanClaim() {
        RScoredSortedSet<String> queue = queue();
        queue.add(1, "zombie-1");
        queue.add(2, "zombie-2");
        queue.add(3, "live-a");
        queue.add(4, "live-b");
        setEntryMarker("live-a");
        setEntryMarker("live-b");

        List<Object> result = evalClaim("live-a", 1);

        assertThat(parseLong(result.get(0))).isEqualTo(1L);
        assertThat(queue.readAll()).containsExactly("live-b");
        assertThat(bucket("live-a").isExists()).as("claim 成功后应删掉自己的 entry 标记").isFalse();
        assertThat(bucket("live-b").isExists()).as("未参与 claim 的存活标记应保留").isTrue();
    }

    /**
     * 存活条目落在 maxRank 窗口外时不能 claim，队列原样保留——公平性靠这条不让抢跑
     */
    @Test
    @DisplayName("窗口外的存活条目不 claim 且队列不动")
    void shouldNotClaimWhenOutsideHeadWindow() {
        RScoredSortedSet<String> queue = queue();
        queue.add(1, "live-a");
        queue.add(2, "live-b");
        setEntryMarker("live-a");
        setEntryMarker("live-b");

        List<Object> result = evalClaim("live-b", 1);

        assertThat(parseLong(result.get(0))).isEqualTo(0L);
        assertThat(queue.readAll()).containsExactlyInAnyOrder("live-a", "live-b");
    }

    // ==================== 工具 ====================

    private void acquire(String ticket, List<String> grantedOrder, List<String> timedOut,
                         CountDownLatch latch, Runnable whileHolding) {
        limiter.acquire(FairDistributedRateLimiter.AcquireRequest.builder()
                .maxWaitMillis(QUEUE_WAIT_MILLIS)
                .onAcquired(() -> {
                    grantedOrder.add(ticket);
                    whileHolding.run();
                    latch.countDown();
                })
                .onTimeout(() -> timedOut.add(ticket))
                .onAcquiredExecutor(businessPool)
                .build());
    }

    private RScoredSortedSet<String> queue() {
        return redisson.getScoredSortedSet(name + ":queue", StringCodec.INSTANCE);
    }

    private RBucket<String> bucket(String requestId) {
        return redisson.getBucket(name + ":entry:" + requestId, StringCodec.INSTANCE);
    }

    private void setEntryMarker(String requestId) {
        bucket(requestId).set("1", Duration.ofMinutes(1));
    }

    private List<Object> evalClaim(String requestId, int maxRank) {
        String script;
        try {
            script = new String(
                    new ClassPathResource(LUA_PATH).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("读取 Lua 脚本失败：" + LUA_PATH, ex);
        }
        return redisson.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE,
                script,
                RScript.ReturnType.LIST,
                List.of(name + ":queue"),
                requestId,
                String.valueOf(maxRank),
                name + ":entry:");
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(AWAIT_SECONDS, TimeUnit.SECONDS)) {
                fail("等待超时");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private static long parseLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
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
