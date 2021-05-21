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

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public class DistributedTraceIds {
    private LinkedList<DistributedTraceId> relatedGlobalTraces;

    public DistributedTraceIds() {
        relatedGlobalTraces = new LinkedList<>();
    }

    public List<DistributedTraceId> getRelatedGlobalTraces() {
        return Collections.unmodifiableList(relatedGlobalTraces);
    }

    public void append(DistributedTraceId distributedTraceId) {
        //如果relatedGlobalTraces不为空 并且 头是NewDistributedTraceId 需要删除头
        //因为当前TraceSegment和父segment是同一个分布式traceid的关系，当前TraceSegment不能使用另一个不同的traceid
        if (relatedGlobalTraces.size() > 0 && relatedGlobalTraces.getFirst() instanceof NewDistributedTraceId) {
            relatedGlobalTraces.removeFirst();
        }
        //头不是NewDistributedTraceId，就有可能是PropagatedTraceId，具体还得细看。
        //不包括，就加进去，正常来讲加进去的一定是放在头的位置，作为一致的distributedTraceId
        if (!relatedGlobalTraces.contains(distributedTraceId)) {
            relatedGlobalTraces.add(distributedTraceId);
        }
    }
}
