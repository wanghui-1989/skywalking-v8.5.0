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

package org.apache.skywalking.apm.plugin.tomcat78x;

import java.lang.reflect.Method;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.catalina.connector.Request;
import org.apache.skywalking.apm.agent.core.context.CarrierItem;
import org.apache.skywalking.apm.agent.core.context.ContextCarrier;
import org.apache.skywalking.apm.agent.core.context.ContextManager;
import org.apache.skywalking.apm.agent.core.context.tag.Tags;
import org.apache.skywalking.apm.agent.core.context.trace.AbstractSpan;
import org.apache.skywalking.apm.agent.core.context.trace.SpanLayer;
import org.apache.skywalking.apm.agent.core.context.trace.TraceSegment;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.EnhancedInstance;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.InstanceMethodsAroundInterceptor;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.MethodInterceptResult;
import org.apache.skywalking.apm.agent.core.util.CollectionUtil;
import org.apache.skywalking.apm.agent.core.util.MethodUtil;
import org.apache.skywalking.apm.network.trace.component.ComponentsDefine;
import org.apache.skywalking.apm.util.StringUtil;
import org.apache.tomcat.util.http.Parameters;

/**
 * {@link TomcatInvokeInterceptor} fetch the serialized context data by using {@link
 * HttpServletRequest#getHeader(String)}. The {@link TraceSegment#refs} of current trace segment will reference to the
 * trace segment id of the previous level if the serialized context is not null.
 *
 * 对org.apache.catalina.core.StandardHostValve
 *          #invoke(org.apache.catalina.connector.Request, org.apache.catalina.connector.Response);
 *          #throwable(org.apache.catalina.connector.Request, org.apache.catalina.connector.Response,
 *          java.lang.Throwable);
 * 两个方法进行拦截。
 * 选择中间件拦截点的时候，一定要确定是单线程执行，比如tomcat，是有线程池的，
 * 线程池中选出一个线程来处理请求，执行java web的controller方法。选的就是这个线程执行的方法。
 * 业务中，多线程要用RunnableWrapper,CallableWrapper等包装才会被拦截。
 */
public class TomcatInvokeInterceptor implements InstanceMethodsAroundInterceptor {

    private static boolean IS_SERVLET_GET_STATUS_METHOD_EXIST;
    private static final String SERVLET_RESPONSE_CLASS = "javax.servlet.http.HttpServletResponse";
    private static final String GET_STATUS_METHOD = "getStatus";

    static {
        IS_SERVLET_GET_STATUS_METHOD_EXIST = MethodUtil.isMethodExist(
            TomcatInvokeInterceptor.class.getClassLoader(), SERVLET_RESPONSE_CLASS, GET_STATUS_METHOD);
    }

    /**
     * * The {@link TraceSegment#refs} of current trace segment will reference to the trace segment id of the previous
     * level if the serialized context is not null.
     *
     * @param result change this result, if you want to truncate the method.
     */
    @Override
    public void beforeMethod(EnhancedInstance objInst, Method method, Object[] allArguments, Class<?>[] argumentsTypes,
                             MethodInterceptResult result) throws Throwable {
        Request request = (Request) allArguments[0];
        ContextCarrier contextCarrier = new ContextCarrier();

        //这里不是像客户端一样序列化数据到header。在客户端序列化时调用这个方法，
        //将一些数据拼成固定的kv，set到header。服务端需要先拿到客户端set了哪些key名，然后才能根据key名获取对应value，
        // 这里就是使用和客户端一样的序列化方法，只是为了拿到这些key名称，因次此处value值一定为空字符串
        CarrierItem next = contextCarrier.items();
        while (next.hasNext()) {
            next = next.next();
            //遍历key，去request.header拿value
            next.setHeadValue(request.getHeader(next.getHeadKey()));
        }

        //每个线程在afterMethod或者异常捕获里都会删除本次创建的context，所以需要为当期线程创建一个context
        //这里会在创建的context中，将EntrySpan压栈。
        AbstractSpan span = ContextManager.createEntrySpan(request.getRequestURI(), contextCarrier);
        Tags.URL.set(span, request.getRequestURL().toString());
        Tags.HTTP.METHOD.set(span, request.getMethod());
        span.setComponent(ComponentsDefine.TOMCAT);
        SpanLayer.asHttp(span);

        if (TomcatPluginConfig.Plugin.Tomcat.COLLECT_HTTP_PARAMS) {
            //手机http params 要考虑request流是否可重复读，这里读的是org.apache.coyote.Request对象
            collectHttpParam(request, span);
        }
    }

    @Override
    public Object afterMethod(EnhancedInstance objInst, Method method, Object[] allArguments, Class<?>[] argumentsTypes,
                              Object ret) throws Throwable {
        Request request = (Request) allArguments[0];
        HttpServletResponse response = (HttpServletResponse) allArguments[1];

        //取栈顶span，这里拿到的是EntrySpan
        AbstractSpan span = ContextManager.activeSpan();
        if (IS_SERVLET_GET_STATUS_METHOD_EXIST && response.getStatus() >= 400) {
            //服务器错误
            span.errorOccurred();
            Tags.STATUS_CODE.set(span, Integer.toString(response.getStatus()));
        }
        // Active HTTP parameter collection automatically in the profiling context.
        if (!TomcatPluginConfig.Plugin.Tomcat.COLLECT_HTTP_PARAMS && span.isProfiling()) {
            collectHttpParam(request, span);
        }
        ContextManager.getRuntimeContext().remove(Constants.FORWARD_REQUEST_FLAG);
        //这里弹栈，删除context，闭环操作
        ContextManager.stopSpan();
        return ret;
    }

    @Override
    public void handleMethodException(EnhancedInstance objInst, Method method, Object[] allArguments,
                                      Class<?>[] argumentsTypes, Throwable t) {
        AbstractSpan span = ContextManager.activeSpan();
        span.log(t);
    }

    private void collectHttpParam(Request request, AbstractSpan span) {
        final Map<String, String[]> parameterMap = new HashMap<>();
        final org.apache.coyote.Request coyoteRequest = request.getCoyoteRequest();
        final Parameters parameters = coyoteRequest.getParameters();
        for (final Enumeration<String> names = parameters.getParameterNames(); names.hasMoreElements(); ) {
            final String name = names.nextElement();
            parameterMap.put(name, parameters.getParameterValues(name));
        }

        if (!parameterMap.isEmpty()) {
            String tagValue = CollectionUtil.toString(parameterMap);
            tagValue = TomcatPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD > 0 ?
                StringUtil.cut(tagValue, TomcatPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD) :
                tagValue;
            Tags.HTTP.PARAMS.set(span, tagValue);
        }
    }
}
