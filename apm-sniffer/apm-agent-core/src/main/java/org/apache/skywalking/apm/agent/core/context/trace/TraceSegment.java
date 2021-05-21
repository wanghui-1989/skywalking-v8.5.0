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

package org.apache.skywalking.apm.agent.core.context.trace;

import java.util.LinkedList;
import java.util.List;
import org.apache.skywalking.apm.agent.core.conf.Config;
import org.apache.skywalking.apm.agent.core.context.ids.DistributedTraceId;
import org.apache.skywalking.apm.agent.core.context.ids.DistributedTraceIds;
import org.apache.skywalking.apm.agent.core.context.ids.GlobalIdGenerator;
import org.apache.skywalking.apm.agent.core.context.ids.NewDistributedTraceId;
import org.apache.skywalking.apm.network.language.agent.v3.SegmentObject;

/**
 * {@link TraceSegment} is a segment or fragment of the distributed trace. See https://github.com/opentracing/specification/blob/master/specification.md#the-opentracing-data-model
 * A {@link TraceSegment} means the segment, which exists in current {@link Thread}. And the distributed trace is formed
 * by multi {@link TraceSegment}s, because the distributed trace crosses multi-processes, multi-threads. <p>
 *
 * TraceSegment是分布式跟踪的一个片段或片段。看下https://github.com/opentracing/specification/blob/master/specification
 * .md#the-opentracing数据模型。TraceSegment是指存在于当前线程中的段。分布式追踪是由多个跟踪段组成的，因为分布式跟踪跨越多进程、多线程。
 */
public class TraceSegment {
    /**
     * The id of this trace segment. Every segment has its unique-global-id.
     * 即trace id
     */
    private String traceSegmentId;

    /**
     * The refs of parent trace segments, except the primary one. For most RPC call, {@link #refs} contains only one
     * element, but if this segment is a start span of batch process, the segment faces multi parents, at this moment,
     * we use this {@code #refs} to link them.
     * <p>
     * This field will not be serialized. Keeping this field is only for quick accessing.
     * 父跟踪段的引用，主跟踪段除外。对于大多数RPC调用，refs只包含一个元素，但如果此段是批处理的起始范围，则该段将面对多个父级，
     * 此时，我们使用此#refs链接它们。此字段将不会序列化。保留此字段仅用于快速访问。
     */
    private List<TraceSegmentRef> refs;

    /**
     * The spans belong to this trace segment. They all have finished. All active spans are hold and controlled by
     * "skywalking-api" module.
     * 属于此TraceSegment的所有Span，他们都完成了。所有活动中的Span都由“skywalking-api”模块持有和控制。
     */
    private List<AbstractTracingSpan> spans;

    /**
     * The <code>relatedGlobalTraces</code> represent a set of all related trace. Most time it contains only one
     * element, because only one parent {@link TraceSegment} exists, but, in batch scenario, the num becomes greater
     * than 1, also meaning multi-parents {@link TraceSegment}. <p> The difference between
     * <code>relatedGlobalTraces</code> and {@link #refs} is: {@link #refs} targets this {@link TraceSegment}'s direct
     * parent, <p> and <p> <code>relatedGlobalTraces</code> targets this {@link TraceSegment}'s related call chain, a
     * call chain contains multi {@link TraceSegment}s, only using {@link #refs} is not enough for analysis and ui.
     *
     * relatedGlobalTraces表示所有相关跟踪的集合。 大多数情况下，它仅包含一个元素，因为仅存在一个父级TraceSegment，
     * 但是在批处理方案中，num变得大于1，这也意味着多父级TraceSegment。
     * relatedGlobalTraces和refs之间的区别是：refs定位到该TraceSegment的直接父级，
     * 和
     * relatedGlobalTraces针对此TraceSegment的相关调用链，一个调用链包含多个TraceSegments，仅使用引用不足以进行分析和ui。
     *
     * 从DistributedTraceId的含义来理解这个值。分布式追踪中用到。
     */
    private DistributedTraceIds relatedGlobalTraces;

    private boolean ignore = false;

    private boolean isSizeLimited = false;

    private final long createTime;

    /**
     * Create a default/empty trace segment, with current time as start time, and generate a new segment id.
     */
    public TraceSegment() {
        //生成trace id
        this.traceSegmentId = GlobalIdGenerator.generate();
        //初始化span栈
        this.spans = new LinkedList<>();
        this.relatedGlobalTraces = new DistributedTraceIds();
        //添加第一个DistributedTraceId
        this.relatedGlobalTraces.append(new NewDistributedTraceId());
        this.createTime = System.currentTimeMillis();
    }

    /**
     * Establish the link between this segment and its parents.
     * 建立此segment与其父segment之间的链接。
     *
     * @param refSegment {@link TraceSegmentRef}
     */
    public void ref(TraceSegmentRef refSegment) {
        if (refs == null) {
            refs = new LinkedList<>();
        }
        if (!refs.contains(refSegment)) {
            refs.add(refSegment);
        }
    }

    /**
     * Establish the line between this segment and all relative global trace ids.
     * 在该segment和所有相关全局trace id之间建立一条线。
     */
    public void relatedGlobalTraces(DistributedTraceId distributedTraceId) {
        relatedGlobalTraces.append(distributedTraceId);
    }

    /**
     * After {@link AbstractSpan} is finished, as be controller by "skywalking-api" module, notify the {@link
     * TraceSegment} to archive it.
     */
    public void archive(AbstractTracingSpan finishedSpan) {
        spans.add(finishedSpan);
    }

    /**
     * Finish this {@link TraceSegment}. <p> return this, for chaining
     */
    public TraceSegment finish(boolean isSizeLimited) {
        this.isSizeLimited = isSizeLimited;
        return this;
    }

    public String getTraceSegmentId() {
        return traceSegmentId;
    }

    public boolean hasRef() {
        return !(refs == null || refs.size() == 0);
    }

    public List<TraceSegmentRef> getRefs() {
        return refs;
    }

    public List<DistributedTraceId> getRelatedGlobalTraces() {
        return relatedGlobalTraces.getRelatedGlobalTraces();
    }

    public boolean isSingleSpanSegment() {
        return this.spans != null && this.spans.size() == 1;
    }

    public boolean isIgnore() {
        return ignore;
    }

    public void setIgnore(boolean ignore) {
        this.ignore = ignore;
    }

    /**
     * This is a high CPU cost method, only called when sending to collector or test cases.
     *
     * @return the segment as GRPC service parameter
     */
    public SegmentObject transform() {
        SegmentObject.Builder traceSegmentBuilder = SegmentObject.newBuilder();
        traceSegmentBuilder.setTraceId(getRelatedGlobalTraces().get(0).getId());
        /*
         * Trace Segment
         */
        traceSegmentBuilder.setTraceSegmentId(this.traceSegmentId);
        // Don't serialize TraceSegmentReference

        // SpanObject
        for (AbstractTracingSpan span : this.spans) {
            traceSegmentBuilder.addSpans(span.transform());
        }
        traceSegmentBuilder.setService(Config.Agent.SERVICE_NAME);
        traceSegmentBuilder.setServiceInstance(Config.Agent.INSTANCE_NAME);
        traceSegmentBuilder.setIsSizeLimited(this.isSizeLimited);

        return traceSegmentBuilder.build();
    }

    @Override
    public String toString() {
        return "TraceSegment{" + "traceSegmentId='" + traceSegmentId + '\'' + ", refs=" + refs + ", spans=" + spans + ", relatedGlobalTraces=" + relatedGlobalTraces + '}';
    }

    public long createTime() {
        return this.createTime;
    }
}
