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
 *
 */

package org.apache.skywalking.apm.agent.core.sampling;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.skywalking.apm.agent.core.boot.BootService;
import org.apache.skywalking.apm.agent.core.boot.DefaultImplementor;
import org.apache.skywalking.apm.agent.core.boot.DefaultNamedThreadFactory;
import org.apache.skywalking.apm.agent.core.boot.ServiceManager;
import org.apache.skywalking.apm.agent.core.conf.Config;
import org.apache.skywalking.apm.agent.core.conf.dynamic.ConfigurationDiscoveryService;
import org.apache.skywalking.apm.agent.core.conf.dynamic.watcher.SamplingRateWatcher;
import org.apache.skywalking.apm.agent.core.context.trace.TraceSegment;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.util.RunnableWithExceptionProtection;

/**
 * The <code>SamplingService</code> take charge of how to sample the {@link TraceSegment}. Every {@link TraceSegment}s
 * have been traced, but, considering CPU cost of serialization/deserialization, and network bandwidth, the agent do NOT
 * send all of them to collector, if SAMPLING is on.
 * <p>
 * By default, SAMPLING is on, and  {@link Config.Agent#SAMPLE_N_PER_3_SECS }
 *
 * SamplingService单例，只所有监测接口的采样率，不是一个接口的采样率。
 * 会起一个线程，每3秒重置已采样数量的值为0。每采样一个trace，该值加1。
 * 所以sw不是在3秒内平均间隔多少毫秒采样1次，或者说按接口分组，每个接口采样几次。
 * 而是在一个3秒的时间间隔内，最先到达或者说调用采样的几个trace会被记录，其他的就都不能记录了，直接忽略。
 * 结果就是会有一些接口没有记录下trace。
 */
@DefaultImplementor
public class SamplingService implements BootService {
    private static final ILog LOGGER = LogManager.getLogger(SamplingService.class);

    private volatile boolean on = false;
    //记录实际的已采样数量，如已经记录了3个traceSegment
    private volatile AtomicInteger samplingFactorHolder;
    private volatile ScheduledFuture<?> scheduledFuture;
    //持有采样率配置的值，如每三秒采5个traceSegment
    private SamplingRateWatcher samplingRateWatcher;

    @Override
    public void prepare() {
    }

    @Override
    public void boot() {
        //从配置中获取采样率配置set
        samplingRateWatcher = new SamplingRateWatcher("agent.sample_n_per_3_secs", this);
        ServiceManager.INSTANCE.findService(ConfigurationDiscoveryService.class)
                               .registerAgentConfigChangeWatcher(samplingRateWatcher);

        handleSamplingRateChanged();
    }

    @Override
    public void onComplete() {

    }

    @Override
    public void shutdown() {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
        }
    }

    /**
     * @param operationName The first operation name of the new tracing context.
     * @return true, if sampling mechanism is on, and getDefault the sampling factor successfully.
     */
    public boolean trySampling(String operationName) {
        if (on) {
            int factor = samplingFactorHolder.get();
            if (factor < samplingRateWatcher.getSamplingRate()) {
                return samplingFactorHolder.compareAndSet(factor, factor + 1);
            } else {
                return false;
            }
        }
        return true;
    }

    /**
     * Increase the sampling factor by force, to avoid sampling too many traces. If many distributed traces require
     * sampled, the trace beginning at local, has less chance to be sampled.
     */
    public void forceSampled() {
        if (on) {
            samplingFactorHolder.incrementAndGet();
        }
    }

    private void resetSamplingFactor() {
        samplingFactorHolder = new AtomicInteger(0);
    }

    /**
     * Handle the samplingRate changed.
     */
    public void handleSamplingRateChanged() {
        if (samplingRateWatcher.getSamplingRate() > 0) {
            if (!on) {
                on = true;
                this.resetSamplingFactor();
                //单线程，处理采样率变更事件
                ScheduledExecutorService service = Executors.newSingleThreadScheduledExecutor(
                    new DefaultNamedThreadFactory("SamplingService"));
                //每3秒重置一次采样率的值，置为0
                scheduledFuture = service.scheduleAtFixedRate(new RunnableWithExceptionProtection(
                    this::resetSamplingFactor, t -> LOGGER.error("unexpected exception.", t)), 0, 3, TimeUnit.SECONDS);
                LOGGER.debug(
                    "Agent sampling mechanism started. Sample {} traces in 3 seconds.",
                    samplingRateWatcher.getSamplingRate()
                );
            }
        } else {
            if (on) {
                if (scheduledFuture != null) {
                    scheduledFuture.cancel(true);
                }
                on = false;
            }
        }
    }
}