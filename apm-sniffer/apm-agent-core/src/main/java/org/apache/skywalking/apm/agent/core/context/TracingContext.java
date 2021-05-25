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

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.locks.ReentrantLock;
import lombok.AccessLevel;
import lombok.Getter;
import org.apache.skywalking.apm.agent.core.boot.ServiceManager;
import org.apache.skywalking.apm.agent.core.conf.Config;
import org.apache.skywalking.apm.agent.core.conf.dynamic.watcher.SpanLimitWatcher;
import org.apache.skywalking.apm.agent.core.context.ids.DistributedTraceId;
import org.apache.skywalking.apm.agent.core.context.ids.PropagatedTraceId;
import org.apache.skywalking.apm.agent.core.context.trace.AbstractSpan;
import org.apache.skywalking.apm.agent.core.context.trace.AbstractTracingSpan;
import org.apache.skywalking.apm.agent.core.context.trace.EntrySpan;
import org.apache.skywalking.apm.agent.core.context.trace.ExitSpan;
import org.apache.skywalking.apm.agent.core.context.trace.ExitTypeSpan;
import org.apache.skywalking.apm.agent.core.context.trace.LocalSpan;
import org.apache.skywalking.apm.agent.core.context.trace.NoopExitSpan;
import org.apache.skywalking.apm.agent.core.context.trace.NoopSpan;
import org.apache.skywalking.apm.agent.core.context.trace.TraceSegment;
import org.apache.skywalking.apm.agent.core.context.trace.TraceSegmentRef;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.profile.ProfileStatusReference;
import org.apache.skywalking.apm.agent.core.profile.ProfileTaskExecutionService;
import org.apache.skywalking.apm.util.StringUtil;

/**
 * The <code>TracingContext</code> represents a core tracing logic controller. It build the final {@link
 * TracingContext}, by the stack mechanism, which is similar with the codes work.
 * <p>
 * In opentracing concept, it means, all spans in a segment tracing context(thread) are CHILD_OF relationship, but no
 * FOLLOW_OF.
 * <p>
 * In skywalking core concept, FOLLOW_OF is an abstract concept when cross-process MQ or cross-thread async/batch tasks
 * happen, we used {@link TraceSegmentRef} for these scenarios. Check {@link TraceSegmentRef} which is from {@link
 * ContextCarrier} or {@link ContextSnapshot}.
 *
 * TracingContext表示核心跟踪逻辑控制器。 它通过堆栈机制构建最终的TracingContext，与代码工作类似。
 * 在opentracing概念中，这意味着段跟踪上下文（线程）中的所有跨度都是CHILD_OF关系，但没有FOLLOW_OF。
 * 在skywalking的核心概念中，当发生跨进程MQ或跨线程异步/批处理任务时，FOLLOW_OF是一个抽象概念，
 * 在这些情况下，我们使用TraceSegmentRef。 检查来自ContextCarrier或ContextSnapshot的TraceSegmentRef。
 *
 */
public class TracingContext implements AbstractTracerContext {
    private static final ILog LOGGER = LogManager.getLogger(TracingContext.class);
    private long lastWarningTimestamp = 0;

    /**
     * @see ProfileTaskExecutionService
     */
    private static ProfileTaskExecutionService PROFILE_TASK_EXECUTION_SERVICE;

    /**
     * The final {@link TraceSegment}, which includes all finished spans.
     * 一个上下文持有一个TraceSegment
     */
    private TraceSegment segment;

    /**
     * Active spans stored in a Stack, usually called 'ActiveSpanStack'. This {@link LinkedList} is the in-memory
     * storage-structure. <p> I use {@link LinkedList#removeLast()}, {@link LinkedList#addLast(Object)} and {@link
     * LinkedList#getLast()} instead of {@link #pop()}, {@link #push(AbstractSpan)}, {@link #peek()}
     * 处于活动中，未完成状态的Span的栈集合
     */
    private LinkedList<AbstractSpan> activeSpanStack = new LinkedList<>();
    /**
     * @since 7.0.0 SkyWalking support lazy injection through {@link ExitTypeSpan#inject(ContextCarrier)}. Due to that,
     * the {@link #activeSpanStack} could be blank by then, this is a pointer forever to the first span, even the main
     * thread tracing has been finished.
     */
    private AbstractSpan firstSpan = null;

    /**
     * A counter for the next span.
     * 根据id升序，还原一个TraceSegment内，多个span的先后执行关系。
     */
    private int spanIdGenerator;

    /**
     * The counter indicates
     */
    @SuppressWarnings("unused") // updated by ASYNC_SPAN_COUNTER_UPDATER
    private volatile int asyncSpanCounter;
    private static final AtomicIntegerFieldUpdater<TracingContext> ASYNC_SPAN_COUNTER_UPDATER =
        AtomicIntegerFieldUpdater.newUpdater(TracingContext.class, "asyncSpanCounter");
    private volatile boolean isRunningInAsyncMode;
    private volatile ReentrantLock asyncFinishLock;

    private volatile boolean running;

    private final long createTime;

    /**
     * profile status
     */
    private final ProfileStatusReference profileStatus;
    @Getter(AccessLevel.PACKAGE)
    private final CorrelationContext correlationContext;
    @Getter(AccessLevel.PACKAGE)
    private final ExtensionContext extensionContext;

    //CDS watcher
    private final SpanLimitWatcher spanLimitWatcher;

    /**
     * Initialize all fields with default value.
     * 构建一个TracingContext上下文
     */
    TracingContext(String firstOPName, SpanLimitWatcher spanLimitWatcher) {
        //创建一个新的TraceSegment，包括新的traceId，DistributedTraceId。
        this.segment = new TraceSegment();
        //span id清零
        this.spanIdGenerator = 0;
        //默认同步模式，即单线程
        isRunningInAsyncMode = false;
        createTime = System.currentTimeMillis();
        running = true;

        // profiling status
        if (PROFILE_TASK_EXECUTION_SERVICE == null) {
            PROFILE_TASK_EXECUTION_SERVICE = ServiceManager.INSTANCE.findService(ProfileTaskExecutionService.class);
        }
        this.profileStatus = PROFILE_TASK_EXECUTION_SERVICE.addProfiling(
            this, segment.getTraceSegmentId(), firstOPName);

        this.correlationContext = new CorrelationContext();
        this.extensionContext = new ExtensionContext();
        this.spanLimitWatcher = spanLimitWatcher;
    }

    /**
     * Inject the context into the given carrier, only when the active span is an exit one.
     *
     * 仅当active span是exit span时，才将上下文注入给定的载体。
     * 即将activeSpanStack的栈顶span，注入carrier。
     *
     * @param carrier to carry the context for crossing process. 跨进程时承载上下文的载体
     * @throws IllegalStateException if (1) the active span isn't an exit one. (2) doesn't include peer. Ref to {@link
     *                               AbstractTracerContext#inject(ContextCarrier)}
     */
    @Override
    public void inject(ContextCarrier carrier) {
        this.inject(this.activeSpan(), carrier);
    }

    /**
     * Inject the context into the given carrier and given span, only when the active span is an exit one. This method
     * wouldn't be opened in {@link ContextManager} like {@link #inject(ContextCarrier)}, it is only supported to be
     * called inside the {@link ExitTypeSpan#inject(ContextCarrier)}
     *
     * 仅当活动span是ExitSpan时，才将上下文注入给定的carrier和给定span。
     * @param carrier  to carry the context for crossing process.
     * @param exitSpan to represent the scope of current injection.
     * @throws IllegalStateException if (1) the span isn't an exit one. (2) doesn't include peer.
     */
    public void inject(AbstractSpan exitSpan, ContextCarrier carrier) {
        if (!exitSpan.isExit()) {
            throw new IllegalStateException("Inject can be done only in Exit Span");
        }

        ExitTypeSpan spanWithPeer = (ExitTypeSpan) exitSpan;
        //peer是当前客户端或者说调用发起方的ip、端口
        String peer = spanWithPeer.getPeer();
        if (StringUtil.isEmpty(peer)) {
            throw new IllegalStateException("Exit span doesn't include meaningful peer information.");
        }

        //DistributedTraceId。获取relatedGlobalTraces中的第一个DistributedTraceId，作为traceId，来跨进程。
        carrier.setTraceId(getReadablePrimaryTraceId());
        //traceSegmentId
        carrier.setTraceSegmentId(this.segment.getTraceSegmentId());
        carrier.setSpanId(exitSpan.getSpanId());
        //配置的服务名，一般为业务系统名称，如：HOTEL
        carrier.setParentService(Config.Agent.SERVICE_NAME);
        //服务实例名
        carrier.setParentServiceInstance(Config.Agent.INSTANCE_NAME);
        //父Endpoint名称
        carrier.setParentEndpoint(first().getOperationName());
        //remote调用发起方，也可以叫客户端的地址，即ip、port
        carrier.setAddressUsedAtClient(peer);
        //将TracingContext中的用户关联上下文，注入到carrier中的用户关联上下文中
        this.correlationContext.inject(carrier);
        //将TracingContext中的扩展上下文，注入到carrier中的扩展上下文中
        this.extensionContext.inject(carrier);
    }

    /**
     * Extract the carrier to build the reference for the pre segment.
     *
     * @param carrier carried the context from a cross-process segment. Ref to {@link AbstractTracerContext#extract(ContextCarrier)}
     */
    @Override
    public void extract(ContextCarrier carrier) {
        //创建跨进程TraceSegment引用指针
        TraceSegmentRef ref = new TraceSegmentRef(carrier);
        //远程segment和当前segment建立父子关系 ChildOf关系
        this.segment.ref(ref);
        //PropagatedTraceId表示从对等方传播过来的DistributedTraceId。
        //应该使用远程父segment的分布式traceId，表示为同一个调用链。只修改了这一个值，标记为同一个trace。
        this.segment.relatedGlobalTraces(new PropagatedTraceId(carrier.getTraceId()));
        AbstractSpan span = this.activeSpan();
        if (span instanceof EntrySpan) {
            //当前是EntrySpan，关联父span
            span.ref(ref);
        }

        //将上一个TraceSegment的用户和扩展上下文 提取到当前segment的上下文中
        carrier.extractExtensionTo(this);
        carrier.extractCorrelationTo(this);
    }

    /**
     * Capture the snapshot of current context.
     * 捕获当前追踪上下文的快照，为跨线程传播做准备
     *
     * @return the snapshot of context for cross-thread propagation Ref to {@link AbstractTracerContext#capture()}
     */
    @Override
    public ContextSnapshot capture() {
        ContextSnapshot snapshot = new ContextSnapshot(
            segment.getTraceSegmentId(),
            activeSpan().getSpanId(),
            getPrimaryTraceId(),
            first().getOperationName(),
            this.correlationContext,
            this.extensionContext
        );

        return snapshot;
    }

    /**
     * Continue the context from the given snapshot of parent thread.
     *
     * @param snapshot from {@link #capture()} in the parent thread. Ref to {@link AbstractTracerContext#continued(ContextSnapshot)}
     */
    @Override
    public void continued(ContextSnapshot snapshot) {
        if (snapshot.isValid()) {
            TraceSegmentRef segmentRef = new TraceSegmentRef(snapshot);
            this.segment.ref(segmentRef);
            this.activeSpan().ref(segmentRef);
            this.segment.relatedGlobalTraces(snapshot.getTraceId());
            this.correlationContext.continued(snapshot);
            this.extensionContext.continued(snapshot);
            this.extensionContext.handle(this.activeSpan());
        }
    }

    /**
     * @return the first global trace id.
     */
    @Override
    public String getReadablePrimaryTraceId() {
        return getPrimaryTraceId().getId();
    }

    private DistributedTraceId getPrimaryTraceId() {
        return segment.getRelatedGlobalTraces().get(0);
    }

    @Override
    public String getSegmentId() {
        return segment.getTraceSegmentId();
    }

    @Override
    public int getSpanId() {
        return activeSpan().getSpanId();
    }

    /**
     * Create an entry span
     *
     * @param operationName most likely a service name
     * @return span instance. Ref to {@link EntrySpan}
     */
    @Override
    public AbstractSpan createEntrySpan(final String operationName) {
        if (isLimitMechanismWorking()) {
            NoopSpan span = new NoopSpan();
            return push(span);
        }
        AbstractSpan entrySpan;
        TracingContext owner = this;
        final AbstractSpan parentSpan = peek();
        final int parentSpanId = parentSpan == null ? -1 : parentSpan.getSpanId();
        if (parentSpan != null && parentSpan.isEntry()) {
            /*
             * Only add the profiling recheck on creating entry span,
             * as the operation name could be overrided.
             */
            profilingRecheck(parentSpan, operationName);
            parentSpan.setOperationName(operationName);
            entrySpan = parentSpan;
            return entrySpan.start();
        } else {
            //起始span，第一个服务提供者，parentSpanId=1，spanId=0。
            //类似于前端对服务端发起http调用，这里是tomcat收到请求的地方，是服务提供者最先收到请求的地方。
            entrySpan = new EntrySpan(
                spanIdGenerator++, parentSpanId,
                operationName, owner
            );
            entrySpan.start();
            //压栈，记录起始栈帧
            return push(entrySpan);
        }
    }

    /**
     * Create a local span
     *
     * @param operationName most likely a local method signature, or business name.
     * @return the span represents a local logic block. Ref to {@link LocalSpan}
     */
    @Override
    public AbstractSpan createLocalSpan(final String operationName) {
        if (isLimitMechanismWorking()) {
            //达到了限制的span数量，创建NoopSpan
            NoopSpan span = new NoopSpan();
            return push(span);
        }
        //对于一个独立的子线程，或者说线程池内的一个线程，一般是在run/call方法执行前会调用createLocalSpan方法，
        //此时栈应该是空的栈，parentSpanId应该为-1，新的LocalSpan是该线程调用链的第一个元素。
        AbstractSpan parentSpan = peek();
        final int parentSpanId = parentSpan == null ? -1 : parentSpan.getSpanId();
        AbstractTracingSpan span = new LocalSpan(spanIdGenerator++, parentSpanId, operationName, this);
        span.start();
        return push(span);
    }

    /**
     * Create an exit span
     *
     * @param operationName most likely a service name of remote
     * @param remotePeer    the network id(ip:port, hostname:port or ip1:port1,ip2,port, etc.). Remote peer could be set
     *                      later, but must be before injecting.
     * @return the span represent an exit point of this segment.
     * @see ExitSpan
     */
    @Override
    public AbstractSpan createExitSpan(final String operationName, final String remotePeer) {
        if (isLimitMechanismWorking()) {
            NoopExitSpan span = new NoopExitSpan(remotePeer);
            return push(span);
        }

        AbstractSpan exitSpan;
        AbstractSpan parentSpan = peek();
        TracingContext owner = this;
        if (parentSpan != null && parentSpan.isExit()) {
            //parentSpan是ExitSpan
            //出现这种情况的可能场景：假设公司自研框架的RPC框架需要支持SkyWalking追踪，我们开发了相关插件，
            //在请求发出之前创建了ExitSpan，此时该ExitSpan压入栈中。因为公司自研RPC框架实际使用了HttpClient来发送请求，
            //经过httpClient插件的增强，又会创建一个ExitSpan，此时取到的parentSpan就是自研框架压入的那个ExitSpan。
            //这里相当于在客户端丢弃了HttpClient的ExitSpan，使用自研框架的ExitSpan。
            exitSpan = parentSpan;
        } else {
            //parentSpan一般是EntrySpan/LocalSpan，基本不会是Null(没想到能出现Null的场景)
            final int parentSpanId = parentSpan == null ? -1 : parentSpan.getSpanId();
            //remotePeer是当前客户端或者说调用发起方的ip、端口
            exitSpan = new ExitSpan(spanIdGenerator++, parentSpanId, operationName, remotePeer, owner);
            push(exitSpan);
        }
        exitSpan.start();
        return exitSpan;
    }

    /**
     * @return the active span of current context, the top element of {@link #activeSpanStack}
     * 当前上下文的active span，即activeSpanStack的栈顶元素。
     */
    @Override
    public AbstractSpan activeSpan() {
        AbstractSpan span = peek();
        if (span == null) {
            throw new IllegalStateException("No active span.");
        }
        return span;
    }

    /**
     * Stop the given span, if and only if this one is the top element of {@link #activeSpanStack}. Because the tracing
     * core must make sure the span must match in a stack module, like any program did.
     *
     * @param span to finish
     */
    @Override
    public boolean stopSpan(AbstractSpan span) {
        AbstractSpan lastSpan = peek();
        if (lastSpan == span) {
            if (lastSpan instanceof AbstractTracingSpan) {
                //是EntrySpan/ExitSpan/LocalSpan
                AbstractTracingSpan toFinishSpan = (AbstractTracingSpan) lastSpan;
                if (toFinishSpan.finish(segment)) {
                    //弹栈
                    pop();
                }
            } else {
                //是NoopSpan/NoopExitSpan
                //弹栈
                pop();
            }
        } else {
            throw new IllegalStateException("Stopping the unexpected span = " + span);
        }

        //处理异步操作，结束TracingContext运行标记位。
        finish();

        return activeSpanStack.isEmpty();
    }

    @Override
    public AbstractTracerContext awaitFinishAsync() {
        if (!isRunningInAsyncMode) {
            synchronized (this) {
                if (!isRunningInAsyncMode) {
                    asyncFinishLock = new ReentrantLock();
                    ASYNC_SPAN_COUNTER_UPDATER.set(this, 0);
                    isRunningInAsyncMode = true;
                }
            }
        }
        ASYNC_SPAN_COUNTER_UPDATER.incrementAndGet(this);
        return this;
    }

    @Override
    public void asyncStop(AsyncSpan span) {
        ASYNC_SPAN_COUNTER_UPDATER.decrementAndGet(this);
        finish();
    }

    @Override
    public CorrelationContext getCorrelationContext() {
        return this.correlationContext;
    }

    /**
     * Re-check current trace need profiling, encase third part plugin change the operation name.
     *
     * @param span          current modify span
     * @param operationName change to operation name
     */
    public void profilingRecheck(AbstractSpan span, String operationName) {
        // only recheck first span
        if (span.getSpanId() != 0) {
            return;
        }

        PROFILE_TASK_EXECUTION_SERVICE.profilingRecheck(this, segment.getTraceSegmentId(), operationName);
    }

    /**
     * Finish this context, and notify all {@link TracingContextListener}s, managed by {@link
     * TracingContext.ListenerManager} and {@link TracingContext.TracingThreadListenerManager}
     */
    private void finish() {
        if (isRunningInAsyncMode) {
            asyncFinishLock.lock();
        }
        try {
            //主线程结束，span栈为空
            boolean isFinishedInMainThread = activeSpanStack.isEmpty() && running;
            if (isFinishedInMainThread) {
                /*
                 * Notify after tracing finished in the main thread.
                 * 触发主线程完成事件，入参是TracingContext
                 */
                TracingThreadListenerManager.notifyFinish(this);
            }

            if (isFinishedInMainThread && (!isRunningInAsyncMode || asyncSpanCounter == 0)) {
                TraceSegment finishedSegment = segment.finish(isLimitMechanismWorking());
                //触发TraceSegment完成事件，入参是TraceSegment
                //这里包括了向channel缓冲中Channels#bufferChannels写入segment数据
                //如果是grpc的话会消费这个缓冲的数据，发送给远程collector。
                //从这里也可以看到不是完成一个span，写一次缓冲，而是主线程完成，整个segment结束，
                //将整个segment的数据一次性写入缓冲。缺点是如果中间一个span出了问题，已完成的span数据不会被发送，
                //会丢失整个segment的数据，对定位问题不利。
                TracingContext.ListenerManager.notifyFinish(finishedSegment);
                running = false;
            }
        } finally {
            if (isRunningInAsyncMode) {
                asyncFinishLock.unlock();
            }
        }
    }

    /**
     * The <code>ListenerManager</code> represents an event notify for every registered listener, which are notified
     * when the <code>TracingContext</code> finished, and {@link #segment} is ready for further process.
     * ListenerManager为每个注册的监听器表示一个事件通知，当TracingContext完成，并且segment准备好进行进一步处理时，会通知这些监听器。
     */
    public static class ListenerManager {
        private static List<TracingContextListener> LISTENERS = new LinkedList<>();

        /**
         * Add the given {@link TracingContextListener} to {@link #LISTENERS} list.
         *
         * @param listener the new listener.
         */
        public static synchronized void add(TracingContextListener listener) {
            LISTENERS.add(listener);
        }

        /**
         * Notify the {@link TracingContext.ListenerManager} about the given {@link TraceSegment} have finished. And
         * trigger {@link TracingContext.ListenerManager} to notify all {@link #LISTENERS} 's {@link
         * TracingContextListener#afterFinished(TraceSegment)}
         *
         * 通知TracingContext.ListenerManager给定的TraceSegment已完成。
         * 并触发TracingContext.ListenerManager以通知所有侦听器的TracingContextListener.afterFinished（TraceSegment）
         *
         * @param finishedSegment the segment that has finished
         */
        static void notifyFinish(TraceSegment finishedSegment) {
            for (TracingContextListener listener : LISTENERS) {
                listener.afterFinished(finishedSegment);
            }
        }

        /**
         * Clear the given {@link TracingContextListener}
         */
        public static synchronized void remove(TracingContextListener listener) {
            LISTENERS.remove(listener);
        }

    }

    /**
     * The <code>ListenerManager</code> represents an event notify for every registered listener, which are notified
     */
    public static class TracingThreadListenerManager {
        private static List<TracingThreadListener> LISTENERS = new LinkedList<>();

        public static synchronized void add(TracingThreadListener listener) {
            LISTENERS.add(listener);
        }

        static void notifyFinish(TracingContext finishedContext) {
            for (TracingThreadListener listener : LISTENERS) {
                listener.afterMainThreadFinish(finishedContext);
            }
        }

        public static synchronized void remove(TracingThreadListener listener) {
            LISTENERS.remove(listener);
        }
    }

    /**
     * @return the top element of 'ActiveSpanStack', and remove it.
     */
    private AbstractSpan pop() {
        return activeSpanStack.removeLast();
    }

    /**
     * Add a new Span at the top of 'ActiveSpanStack'
     *
     * @param span the {@code span} to push
     */
    private AbstractSpan push(AbstractSpan span) {
        if (firstSpan == null) {
            firstSpan = span;
        }
        activeSpanStack.addLast(span);
        this.extensionContext.handle(span);
        return span;
    }

    /**
     * @return the top element of 'ActiveSpanStack' only.
     */
    private AbstractSpan peek() {
        if (activeSpanStack.isEmpty()) {
            return null;
        }
        return activeSpanStack.getLast();
    }

    private AbstractSpan first() {
        return firstSpan;
    }

    /**
     * 是否达到了工作极限
     * 在配置文件中，我们可以限制每个Segment中span的数量不超过某个值，避免过多的span创建，对内存造成影响。
     * 也可以根据此数值来估算trace需要的内存量。
     * 默认SPAN_LIMIT_PER_SEGMENT=300个
     * @return
     */
    private boolean isLimitMechanismWorking() {
        if (spanIdGenerator >= spanLimitWatcher.getSpanLimit()) {
            long currentTimeMillis = System.currentTimeMillis();
            if (currentTimeMillis - lastWarningTimestamp > 30 * 1000) {
                LOGGER.warn(
                    new RuntimeException("Shadow tracing context. Thread dump"),
                    "More than {} spans required to create", spanLimitWatcher.getSpanLimit()
                );
                lastWarningTimestamp = currentTimeMillis;
            }
            return true;
        } else {
            return false;
        }
    }

    public long createTime() {
        return this.createTime;
    }

    public ProfileStatusReference profileStatus() {
        return this.profileStatus;
    }
}
