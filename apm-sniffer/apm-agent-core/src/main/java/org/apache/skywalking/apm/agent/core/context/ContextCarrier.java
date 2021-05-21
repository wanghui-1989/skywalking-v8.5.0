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

import java.io.Serializable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.apache.skywalking.apm.agent.core.base64.Base64;
import org.apache.skywalking.apm.agent.core.conf.Constants;
import org.apache.skywalking.apm.util.StringUtil;

/**
 * {@link ContextCarrier} is a data carrier of {@link TracingContext}. It holds the snapshot (current state) of {@link
 * TracingContext}.
 * <p>
 *
 * 追踪上下文TracingContext的序列化数据携带者，是一种快照数据，用于跨进程、线程使用。
 */
@Setter(AccessLevel.PACKAGE)
public class ContextCarrier implements Serializable {
    /**
     * 用的DistributedTraceId
     */
    @Getter
    private String traceId;
    /**
     * The segment id of the parent.
     * 父TraceSegment的segment id
     */
    @Getter
    private String traceSegmentId;
    /**
     * The span id in the parent segment.
     * 父TraceSegment中ExitSpan的span id
     */
    @Getter
    private int spanId = -1;
    /**
     * 服务名称，一般为业务系统名，如:HOTEL
     */
    @Getter
    private String parentService = Constants.EMPTY_STRING;
    /**
     * 服务实例名称
     */
    @Getter
    private String parentServiceInstance = Constants.EMPTY_STRING;
    /**
     * The endpoint(entrance URI/method signature) of the parent service.
     * 父服务的endpoint，比如http.url
     */
    @Getter
    private String parentEndpoint;
    /**
     * The network address(ip:port, hostname:port) used in the parent service to access the current service.
     * remote调用发起方，也可以叫客户端的地址，即ip、port
     */
    @Getter
    private String addressUsedAtClient;
    /**
     * The extension context contains the optional context to enhance the analysis in some certain scenarios.
     * 扩展上下文包含可选上下文，以增强某些特定场景中的分析。
     */
    @Getter(AccessLevel.PACKAGE)
    private ExtensionContext extensionContext = new ExtensionContext();
    /**
     * User's custom context container. The context propagates with the main tracing context.
     * 用户的自定义上下文容器。上下文与主跟踪上下文一起传播。
     */
    @Getter(AccessLevel.PACKAGE)
    private CorrelationContext correlationContext = new CorrelationContext();

    /**
     * @return the list of items, which could exist in the current tracing context.
     */
    public CarrierItem items() {
        SW8ExtensionCarrierItem sw8ExtensionCarrierItem = new SW8ExtensionCarrierItem(extensionContext, null);
        SW8CorrelationCarrierItem sw8CorrelationCarrierItem = new SW8CorrelationCarrierItem(
            correlationContext, sw8ExtensionCarrierItem);
        SW8CarrierItem sw8CarrierItem = new SW8CarrierItem(this, sw8CorrelationCarrierItem);
        return new CarrierItemHead(sw8CarrierItem);
    }

    /**
     * @return the injector for the extension context.
     */
    public ExtensionInjector extensionInjector() {
        return new ExtensionInjector(extensionContext);
    }

    /**
     * Extract the extension context to tracing context
     */
    void extractExtensionTo(TracingContext tracingContext) {
        tracingContext.getExtensionContext().extract(this);
        // The extension context could have field not to propagate further, so, must use the this.* to process.
        this.extensionContext.handle(tracingContext.activeSpan());
    }

    /**
     * Extract the correlation context to tracing context
     */
    void extractCorrelationTo(TracingContext tracingContext) {
        tracingContext.getCorrelationContext().extract(this);
        // The correlation context could have field not to propagate further, so, must use the this.* to process.
        this.correlationContext.handle(tracingContext.activeSpan());
    }

    /**
     * Serialize this {@link ContextCarrier} to a {@link String}, with '|' split.
     *
     * @return the serialization string.
     */
    String serialize(HeaderVersion version) {
        if (this.isValid(version)) {
            return StringUtil.join(
                '-',
                "1",
                Base64.encode(this.getTraceId()),
                Base64.encode(this.getTraceSegmentId()),
                this.getSpanId() + "",
                Base64.encode(this.getParentService()),
                Base64.encode(this.getParentServiceInstance()),
                Base64.encode(this.getParentEndpoint()),
                Base64.encode(this.getAddressUsedAtClient())
            );
        }
        return "";
    }

    /**
     * Initialize fields with the given text.
     *
     * @param text carries {@link #traceSegmentId} and {@link #spanId}, with '|' split.
     */
    ContextCarrier deserialize(String text, HeaderVersion version) {
        if (text == null) {
            return this;
        }
        if (HeaderVersion.v3.equals(version)) {
            String[] parts = text.split("-", 8);
            if (parts.length == 8) {
                try {
                    // parts[0] is sample flag, always trace if header exists.
                    this.traceId = Base64.decode2UTFString(parts[1]);
                    this.traceSegmentId = Base64.decode2UTFString(parts[2]);
                    this.spanId = Integer.parseInt(parts[3]);
                    this.parentService = Base64.decode2UTFString(parts[4]);
                    this.parentServiceInstance = Base64.decode2UTFString(parts[5]);
                    this.parentEndpoint = Base64.decode2UTFString(parts[6]);
                    this.addressUsedAtClient = Base64.decode2UTFString(parts[7]);
                } catch (IllegalArgumentException ignored) {

                }
            }
        }
        return this;
    }

    public boolean isValid() {
        return isValid(HeaderVersion.v3);
    }

    /**
     * Make sure this {@link ContextCarrier} has been initialized.
     *
     * @return true for unbroken {@link ContextCarrier} or no-initialized. Otherwise, false;
     */
    boolean isValid(HeaderVersion version) {
        if (HeaderVersion.v3 == version) {
            return StringUtil.isNotEmpty(traceId)
                && StringUtil.isNotEmpty(traceSegmentId)
                && getSpanId() > -1
                && StringUtil.isNotEmpty(parentService)
                && StringUtil.isNotEmpty(parentServiceInstance)
                && StringUtil.isNotEmpty(parentEndpoint)
                && StringUtil.isNotEmpty(addressUsedAtClient);
        }
        return false;
    }

    public enum HeaderVersion {
        v3
    }
}
