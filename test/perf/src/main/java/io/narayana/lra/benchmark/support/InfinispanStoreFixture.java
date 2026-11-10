/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.benchmark.support;

import io.narayana.lra.coordinator.infinispan.InfinispanObjectStore;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;

public final class InfinispanStoreFixture {

    private static final String CACHE_NAME = "lra-objectstore";

    private EmbeddedCacheManager cacheManager;
    private InfinispanObjectStore store;

    public void start() {
        GlobalConfigurationBuilder globalConfig = new GlobalConfigurationBuilder();
        globalConfig.nonClusteredDefault();
        cacheManager = new DefaultCacheManager(globalConfig.build());
        cacheManager.defineConfiguration(CACHE_NAME, new ConfigurationBuilder().build());
        Cache<String, byte[]> cache = cacheManager.getCache(CACHE_NAME);
        InfinispanObjectStore.setCache(cache);
        store = new InfinispanObjectStore();
    }

    public void stop() {
        InfinispanObjectStore.setCache(null);
        if (cacheManager != null) {
            cacheManager.stop();
            cacheManager = null;
        }
    }

    public InfinispanObjectStore getStore() {
        return store;
    }

    public Cache<String, byte[]> getCache() {
        return cacheManager.getCache(CACHE_NAME);
    }
}
