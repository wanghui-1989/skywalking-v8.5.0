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

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.apache.skywalking.apm.util.StringUtil;

public final class GlobalIdGenerator {
    private static final String PROCESS_ID = UUID.randomUUID().toString().replaceAll("-", "");
    private static final ThreadLocal<IDContext> THREAD_ID_SEQUENCE = ThreadLocal.withInitial(
        () -> new IDContext(System.currentTimeMillis(), (short) 0));

    private GlobalIdGenerator() {
    }

    /**
     * Generate a new id, combined by three parts.
     * <p>
     * The first one represents application instance id.
     * <p>
     * The second one represents thread id.
     * <p>
     * The third one also has two parts, 1) a timestamp, measured in milliseconds 2) a seq, in current thread, between
     * 0(included) and 9999(included)
     *
     * 创建TraceSegment时，会调用两次该方法，一个是生成traceSegmentId，一个是生成DistributedTraceId，先后调用。
     * 一般都会在同一毫秒内执行完毕。skywalking的traceid生成规则是，在一毫秒内可以创建0-9999(一共一万个值)，也就是一万个traceId。
     * 因为创建TraceSegment时会调用两次该方法，所以这10000个值最大是要打个对折，即一毫秒内最多能创建5000个左右的traceId。
     *
     * @return unique id to represent a trace or segment
     */
    public static String generate() {
        //trace id没有做对齐填充，只是将这些数据用.拼接到一起
        return StringUtil.join(
            '.',
            PROCESS_ID,
            String.valueOf(Thread.currentThread().getId()),
            String.valueOf(THREAD_ID_SEQUENCE.get().nextSeq())
        );
    }

    private static class IDContext {
        private long lastTimestamp;
        private short threadSeq;

        // Just for considering time-shift-back only.
        private long runRandomTimestamp;
        private int lastRandomValue;

        private IDContext(long lastTimestamp, short threadSeq) {
            //传入调用构造器时的当前时间
            this.lastTimestamp = lastTimestamp;
            this.threadSeq = threadSeq;
        }

        private long nextSeq() {
            return timestamp() * 10000 + nextThreadSeq();
        }

        private long timestamp() {
            long currentTimeMillis = System.currentTimeMillis();
            //当前时间小于构造器传入的调用时间
            if (currentTimeMillis < lastTimestamp) {
                // Just for considering time-shift-back by Ops or OS. @hanahmily 's suggestion.
                //由于操作系统或者人为导致的时间回溯。
                if (runRandomTimestamp != currentTimeMillis) {
                    //获取一个新的随线程机值
                    lastRandomValue = ThreadLocalRandom.current().nextInt();
                    //从上一次开始到这一次，新老时间戳无变化
                    runRandomTimestamp = currentTimeMillis;
                }
                //返回该随机值，作为时间戳
                return lastRandomValue;
            } else {
                lastTimestamp = currentTimeMillis;
                return lastTimestamp;
            }
        }

        private short nextThreadSeq() {
            if (threadSeq == 10000) {
                threadSeq = 0;
            }
            return threadSeq++;
        }
    }
}
