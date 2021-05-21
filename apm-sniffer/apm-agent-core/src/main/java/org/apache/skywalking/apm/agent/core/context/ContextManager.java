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

package org.apache.skywalking.apm.agent.core.context;

import java.util.Objects;
import org.apache.skywalking.apm.agent.core.boot.BootService;
import org.apache.skywalking.apm.agent.core.boot.ServiceManager;
import org.apache.skywalking.apm.agent.core.context.trace.AbstractSpan;
import org.apache.skywalking.apm.agent.core.context.trace.TraceSegment;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.sampling.SamplingService;
import org.apache.skywalking.apm.util.StringUtil;

import static org.apache.skywalking.apm.agent.core.conf.Config.Agent.OPERATION_NAME_THRESHOLD;

/**
 * {@link ContextManager} controls the whole context of {@link TraceSegment}. Any {@link TraceSegment} relates to
 * single-thread, so this context use {@link ThreadLocal} to maintain the context, and make sure, since a {@link
 * TraceSegment} starts, all ChildOf spans are in the same context. <p> What is 'ChildOf'?
 * https://github.com/opentracing/specification/blob/master/specification.md#references-between-spans
 *
 * <p> Also, {@link ContextManager} delegates to all {@link AbstractTracerContext}'s major methods.
 *
 * ContextManager控制TraceSegment的整个上下文。任何TraceSegment都与单个线程相关，
 * 因此此上下文使用ThreadLocal来维护上下文，并确保由于TraceSegment启动，所有ChildOf span都在同一上下文中。
 * 什么是“ChildOf”？https://github.com/opentracing/specification/blob/master/specification.md#references-between-spans
 *
 * 此外，ContextManager代理了AbstractTracerContext的所有主要方法。
 * 理解下ChildOf：
 * 假如前端调用HelloController的一个单线程执行的方法hello()，hello方法内部先调用redis获取数据，然后调用dubbo，再调用mysql，
 * 那么这次调用的ChildOf关系表示为：
 *   <p>父Span：HelloController
 *   <p>    子Span：redis
 *   <p>    子Span：dubbo
 *   <p>    子Span：mysql
 * 我们说redis span 和 HelloController span是ChildOf关系。前提是仅限单线程。
 * 一个TraceSegment一定是一个单线程的，sw会为它创建一个上下文。
 * 一个TraceSegment包含多个Span，这些Span都是在这同一个TraceSegment的上下文中。
 */
public class ContextManager implements BootService {
    private static final String EMPTY_TRACE_CONTEXT_ID = "N/A";
    private static final ILog LOGGER = LogManager.getLogger(ContextManager.class);
    //追踪上下文，一个线程对应一个AbstractTracerContext，对应一个TraceSegment
    private static ThreadLocal<AbstractTracerContext> CONTEXT = new ThreadLocal<AbstractTracerContext>();
    //上下文2
    private static ThreadLocal<RuntimeContext> RUNTIME_CONTEXT = new ThreadLocal<RuntimeContext>();
    private static ContextManagerExtendService EXTEND_SERVICE;

    private static AbstractTracerContext getOrCreate(String operationName, boolean forceSampling) {
        AbstractTracerContext context = CONTEXT.get();
        if (context == null) {
            if (StringUtil.isEmpty(operationName)) {
                if (LOGGER.isDebugEnable()) {
                    LOGGER.debug("No operation name, ignore this trace.");
                }
                context = new IgnoredTracerContext();
            } else {
                if (EXTEND_SERVICE == null) {
                    EXTEND_SERVICE = ServiceManager.INSTANCE.findService(ContextManagerExtendService.class);
                }
                //创建TracingContext，不强制采样的话，可能会返回IgnoredTracerContext
                context = EXTEND_SERVICE.createTraceContext(operationName, forceSampling);

            }
            CONTEXT.set(context);
        }
        return context;
    }

    private static AbstractTracerContext get() {
        return CONTEXT.get();
    }

    /**
     * @return the first global trace id when tracing. Otherwise, "N/A".
     */
    public static String getGlobalTraceId() {
        AbstractTracerContext context = CONTEXT.get();
        return Objects.nonNull(context) ? context.getReadablePrimaryTraceId() : EMPTY_TRACE_CONTEXT_ID;
    }

    /**
     * @return the current segment id when tracing. Otherwise, "N/A".
     */
    public static String getSegmentId() {
        AbstractTracerContext context = CONTEXT.get();
        return Objects.nonNull(context) ? context.getSegmentId() : EMPTY_TRACE_CONTEXT_ID;
    }

    /**
     * @return the current span id when tracing. Otherwise, the value is -1.
     */
    public static int getSpanId() {
        AbstractTracerContext context = CONTEXT.get();
        return Objects.nonNull(context) ? context.getSpanId() : -1;
    }

    public static AbstractSpan createEntrySpan(String operationName, ContextCarrier carrier) {
        AbstractSpan span;
        AbstractTracerContext context;
        //限制操作名称长度
        operationName = StringUtil.cut(operationName, OPERATION_NAME_THRESHOLD);
        if (carrier != null && carrier.isValid()) {
            //carrier有效，证明这是跨进程过来的span数据
            SamplingService samplingService = ServiceManager.INSTANCE.findService(SamplingService.class);
            samplingService.forceSampled();
            //获取or创建追踪上下文，因为是跨进程的第一个span，必须要记录下来，所以forceSampling参数传的true，强制采样。
            context = getOrCreate(operationName, true);
            //创建span，压栈！！！
            span = context.createEntrySpan(operationName);
            //从跨进程载体中提取数据到当前上下文，关联上这些分布式操作trace
            context.extract(carrier);
        } else {
            //in jvm的。不强制采样。
            context = getOrCreate(operationName, false);
            //创建span，压栈！！！
            span = context.createEntrySpan(operationName);
        }
        return span;
    }

    public static AbstractSpan createLocalSpan(String operationName) {
        operationName = StringUtil.cut(operationName, OPERATION_NAME_THRESHOLD);
        AbstractTracerContext context = getOrCreate(operationName, false);
        return context.createLocalSpan(operationName);
    }

    public static AbstractSpan createExitSpan(String operationName, ContextCarrier carrier, String remotePeer) {
        if (carrier == null) {
            throw new IllegalArgumentException("ContextCarrier can't be null.");
        }
        operationName = StringUtil.cut(operationName, OPERATION_NAME_THRESHOLD);
        //获取当前traceSegment的上下文，不强制采样的话，可能会返回IgnoredTracerContext
        //这样将导致后续的所有trace都不被记录
        AbstractTracerContext context = getOrCreate(operationName, false);
        //remotePeer是当前客户端或者说调用发起方的ip、端口
        //创建span，压栈！！！
        AbstractSpan span = context.createExitSpan(operationName, remotePeer);
        context.inject(carrier);
        return span;
    }

    public static AbstractSpan createExitSpan(String operationName, String remotePeer) {
        operationName = StringUtil.cut(operationName, OPERATION_NAME_THRESHOLD);
        AbstractTracerContext context = getOrCreate(operationName, false);
        //remotePeer是当前客户端或者说调用发起方的ip、端口
        return context.createExitSpan(operationName, remotePeer);
    }

    public static void inject(ContextCarrier carrier) {
        get().inject(carrier);
    }

    public static void extract(ContextCarrier carrier) {
        if (carrier == null) {
            throw new IllegalArgumentException("ContextCarrier can't be null.");
        }
        if (carrier.isValid()) {
            get().extract(carrier);
        }
    }

    /**
     * 捕获当期线程的上下文快照
     * @return
     */
    public static ContextSnapshot capture() {
        return get().capture();
    }

    public static void continued(ContextSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("ContextSnapshot can't be null.");
        }
        if (!snapshot.isFromCurrent()) {
            //快照不是当期线程的快照
            get().continued(snapshot);
        }
    }

    public static AbstractTracerContext awaitFinishAsync(AbstractSpan span) {
        final AbstractTracerContext context = get();
        AbstractSpan activeSpan = context.activeSpan();
        if (span != activeSpan) {
            throw new RuntimeException("Span is not the active in current context.");
        }
        return context.awaitFinishAsync();
    }

    /**
     * If not sure has the active span, use this method, will be cause NPE when has no active span, use
     * ContextManager::isActive method to determine whether there has the active span.
     */
    public static AbstractSpan activeSpan() {
        return get().activeSpan();
    }

    /**
     * Recommend use ContextManager::stopSpan(AbstractSpan span), because in that way, the TracingContext core could
     * verify this span is the active one, in order to avoid stop unexpected span. If the current span is hard to get or
     * only could get by low-performance way, this stop way is still acceptable.
     */
    public static void stopSpan() {
        final AbstractTracerContext context = get();
        stopSpan(context.activeSpan(), context);
    }

    public static void stopSpan(AbstractSpan span) {
        stopSpan(span, get());
    }

    private static void stopSpan(AbstractSpan span, final AbstractTracerContext context) {
        //弹栈！！！
        if (context.stopSpan(span)) {
            CONTEXT.remove();
            RUNTIME_CONTEXT.remove();
        }
    }

    @Override
    public void prepare() {

    }

    @Override
    public void boot() {
    }

    @Override
    public void onComplete() {

    }

    @Override
    public void shutdown() {

    }

    public static boolean isActive() {
        return get() != null;
    }

    public static RuntimeContext getRuntimeContext() {
        RuntimeContext runtimeContext = RUNTIME_CONTEXT.get();
        if (runtimeContext == null) {
            runtimeContext = new RuntimeContext(RUNTIME_CONTEXT);
            RUNTIME_CONTEXT.set(runtimeContext);
        }

        return runtimeContext;
    }

    public static CorrelationContext getCorrelationContext() {
        final AbstractTracerContext tracerContext = get();
        if (tracerContext == null) {
            return null;
        }

        return tracerContext.getCorrelationContext();
    }

}
