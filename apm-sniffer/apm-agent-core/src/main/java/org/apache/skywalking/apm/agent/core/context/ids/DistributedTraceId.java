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

package org.apache.skywalking.apm.agent.core.context.ids;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

/**
 * The <code>DistributedTraceId</code> presents a distributed call chain.
 * <p>
 * This call chain has a unique (service) entrance,
 * <p>
 * such as: Service : http://www.skywalking.com/cust/query, all the remote, called behind this service, rest remote, db
 * executions, are using the same <code>DistributedTraceId</code> even in different JVM.
 * <p>
 * The <code>DistributedTraceId</code> contains only one string, and can NOT be reset, creating a new instance is the
 * only option.
 *
 * DistributedTraceId表示分布式调用链。
 * 这个调用链有一个唯一的（服务）入口，
 * 例如：服务：http://www.skywalking.com/cust/query，所有在这个服务后面调用的remote，rest remote，db executions，
 * 使用相同的DistributedTraceId，即使在不同的JVM中。
 * DistributedTraceId只包含一个字符串，无法重置，创建新实例是唯一的选项
 */
@RequiredArgsConstructor
@ToString
@EqualsAndHashCode
public abstract class DistributedTraceId {
    @Getter
    private final String id;
}
