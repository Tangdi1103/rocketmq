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
 */

package org.apache.rocketmq.proxy.grpc.v2;

import apache.rocketmq.v2.AckMessageRequest;
import apache.rocketmq.v2.AckMessageResponse;
import apache.rocketmq.v2.ChangeInvisibleDurationRequest;
import apache.rocketmq.v2.ChangeInvisibleDurationResponse;
import apache.rocketmq.v2.Code;
import apache.rocketmq.v2.EndTransactionRequest;
import apache.rocketmq.v2.EndTransactionResponse;
import apache.rocketmq.v2.ForwardMessageToDeadLetterQueueRequest;
import apache.rocketmq.v2.ForwardMessageToDeadLetterQueueResponse;
import apache.rocketmq.v2.HeartbeatRequest;
import apache.rocketmq.v2.HeartbeatResponse;
import apache.rocketmq.v2.MessagingServiceGrpc;
import apache.rocketmq.v2.NotifyClientTerminationRequest;
import apache.rocketmq.v2.NotifyClientTerminationResponse;
import apache.rocketmq.v2.QueryAssignmentRequest;
import apache.rocketmq.v2.QueryAssignmentResponse;
import apache.rocketmq.v2.QueryRouteRequest;
import apache.rocketmq.v2.QueryRouteResponse;
import apache.rocketmq.v2.ReceiveMessageRequest;
import apache.rocketmq.v2.ReceiveMessageResponse;
import apache.rocketmq.v2.SendMessageRequest;
import apache.rocketmq.v2.SendMessageResponse;
import apache.rocketmq.v2.Status;
import apache.rocketmq.v2.TelemetryCommand;
import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.common.thread.ThreadPoolMonitor;
import org.apache.rocketmq.proxy.common.ProxyContext;
import org.apache.rocketmq.proxy.common.StartAndShutdown;
import org.apache.rocketmq.proxy.config.ConfigurationManager;
import org.apache.rocketmq.proxy.config.ProxyConfig;
import org.apache.rocketmq.proxy.grpc.interceptor.InterceptorConstants;
import org.apache.rocketmq.proxy.grpc.v2.common.ResponseBuilder;
import org.apache.rocketmq.proxy.grpc.v2.common.ResponseWriter;
import org.apache.rocketmq.proxy.processor.MessagingProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GrpcMessagingApplication extends MessagingServiceGrpc.MessagingServiceImplBase implements StartAndShutdown {
    private final static Logger log = LoggerFactory.getLogger(LoggerName.PROXY_LOGGER_NAME);

    private final GrpcMessingActivity grpcMessingActivity;

    protected ThreadPoolExecutor routeThreadPoolExecutor;
    protected ThreadPoolExecutor producerThreadPoolExecutor;
    protected ThreadPoolExecutor consumerThreadPoolExecutor;
    protected ThreadPoolExecutor clientManagerThreadPoolExecutor;
    protected ThreadPoolExecutor transactionThreadPoolExecutor;

    protected GrpcMessagingApplication(GrpcMessingActivity grpcMessingActivity) {
        this.grpcMessingActivity = grpcMessingActivity;

        ProxyConfig config = ConfigurationManager.getProxyConfig();
        this.routeThreadPoolExecutor = ThreadPoolMonitor.createAndMonitor(
            config.getGrpcRouteThreadPoolNums(),
            config.getGrpcRouteThreadPoolNums(),
            1,
            TimeUnit.MINUTES,
            "GrpcRouteThreadPool",
            config.getGrpcRouteThreadQueueCapacity()
        );
        this.producerThreadPoolExecutor = ThreadPoolMonitor.createAndMonitor(
            config.getGrpcProducerThreadPoolNums(),
            config.getGrpcProducerThreadPoolNums(),
            1,
            TimeUnit.MINUTES,
            "GrpcProducerThreadPool",
            config.getGrpcProducerThreadQueueCapacity()
        );
        this.consumerThreadPoolExecutor = ThreadPoolMonitor.createAndMonitor(
            config.getGrpcConsumerThreadPoolNums(),
            config.getGrpcConsumerThreadPoolNums(),
            1,
            TimeUnit.MINUTES,
            "GrpcConsumerThreadPool",
            config.getGrpcConsumerThreadQueueCapacity()
        );
        this.clientManagerThreadPoolExecutor = ThreadPoolMonitor.createAndMonitor(
            config.getGrpcClientManagerThreadPoolNums(),
            config.getGrpcClientManagerThreadPoolNums(),
            1,
            TimeUnit.MINUTES,
            "GrpcClientManagerThreadPool",
            config.getGrpcClientManagerThreadQueueCapacity()
        );
        this.transactionThreadPoolExecutor = ThreadPoolMonitor.createAndMonitor(
            config.getGrpcTransactionThreadPoolNums(),
            config.getGrpcTransactionThreadPoolNums(),
            1,
            TimeUnit.MINUTES,
            "GrpcTransactionThreadPool",
            config.getGrpcTransactionThreadQueueCapacity()
        );

        this.init();
    }

    protected void init() {
        GrpcTaskRejectedExecutionHandler rejectedExecutionHandler = new GrpcTaskRejectedExecutionHandler();
        this.routeThreadPoolExecutor.setRejectedExecutionHandler(rejectedExecutionHandler);
        this.routeThreadPoolExecutor.setRejectedExecutionHandler(rejectedExecutionHandler);
        this.producerThreadPoolExecutor.setRejectedExecutionHandler(rejectedExecutionHandler);
        this.consumerThreadPoolExecutor.setRejectedExecutionHandler(rejectedExecutionHandler);
        this.clientManagerThreadPoolExecutor.setRejectedExecutionHandler(rejectedExecutionHandler);
        this.transactionThreadPoolExecutor.setRejectedExecutionHandler(rejectedExecutionHandler);
    }

    public static GrpcMessagingApplication create(MessagingProcessor messagingProcessor) {
        return new GrpcMessagingApplication(new DefaultGrpcMessingActivity(
            messagingProcessor
        ));
    }

    protected Status flowLimitStatus() {
        return ResponseBuilder.buildStatus(Code.TOO_MANY_REQUESTS, "flow limit");
    }

    protected Status convertExceptionToStatus(Throwable t) {
        return ResponseBuilder.buildStatus(t);
    }

    protected <V, T> void addExecutor(ExecutorService executor, ProxyContext context, V request, Runnable runnable,
        StreamObserver<T> responseObserver,
        T executeRejectResponse) {
        executor.submit(new GrpcTask<V, T>(runnable, request, responseObserver, executeRejectResponse));
    }

    protected <V, T> void writeResponse(ProxyContext context, V request, T response, StreamObserver<T> responseObserver,
        Throwable t, Function<Status, T> errorResponseCreator) {
        if (t != null) {
            ResponseWriter.write(
                responseObserver,
                errorResponseCreator.apply(convertExceptionToStatus(t))
            );
        } else {
            ResponseWriter.write(responseObserver, response);
        }
    }

    protected ProxyContext createContext() {
        Context ctx = Context.current();
        ProxyContext context = ProxyContext.create()
            .setLocalAddress(InterceptorConstants.METADATA.get(ctx).get(InterceptorConstants.LOCAL_ADDRESS))
            .setRemoteAddress(InterceptorConstants.METADATA.get(ctx).get(InterceptorConstants.REMOTE_ADDRESS))
            .setClientID(InterceptorConstants.METADATA.get(ctx).get(InterceptorConstants.CLIENT_ID))
            .setLanguage(InterceptorConstants.METADATA.get(ctx).get(InterceptorConstants.LANGUAGE));
        if (ctx.getDeadline() != null) {
            context.setRemainingMs(ctx.getDeadline().timeRemaining(TimeUnit.MILLISECONDS));
        }
        return context;
    }

    @Override
    public void queryRoute(QueryRouteRequest request, StreamObserver<QueryRouteResponse> responseObserver) {
        ProxyContext context = createContext();
        this.addExecutor(this.routeThreadPoolExecutor,
            context,
            request,
            () -> grpcMessingActivity.queryRoute(context, request)
                .whenComplete((response, throwable) -> writeResponse(context, request, response, responseObserver, throwable,
                    status -> QueryRouteResponse.newBuilder().setStatus(status).build())),
            responseObserver,
            QueryRouteResponse.newBuilder().setStatus(flowLimitStatus()).build());
    }

    @Override
    public void heartbeat(HeartbeatRequest request, StreamObserver<HeartbeatResponse> responseObserver) {
        ProxyContext context = createContext();
        this.addExecutor(this.clientManagerThreadPoolExecutor,
            context,
            request,
            () -> grpcMessingActivity.heartbeat(context, request)
                .whenComplete((response, throwable) -> writeResponse(context, request, response, responseObserver, throwable,
                    status -> HeartbeatResponse.newBuilder().setStatus(status).build())),
            responseObserver,
            HeartbeatResponse.newBuilder().setStatus(flowLimitStatus()).build());
    }

    @Override
    public void sendMessage(SendMessageRequest request, StreamObserver<SendMessageResponse> responseObserver) {
        ProxyContext context = createContext();
        this.addExecutor(this.producerThreadPoolExecutor,
            context,
            request,
            () -> grpcMessingActivity.sendMessage(context, request)
                .whenComplete((response, throwable) -> writeResponse(context, request, response, responseObserver, throwable,
                    status -> SendMessageResponse.newBuilder().setStatus(status).build())),
            responseObserver,
            SendMessageResponse.newBuilder().setStatus(flowLimitStatus()).build());
    }

    @Override
    public void queryAssignment(QueryAssignmentRequest request,
        StreamObserver<QueryAssignmentResponse> responseObserver) {
        ProxyContext context = createContext();
        this.addExecutor(this.routeThreadPoolExecutor,
            context,
            request,
            () -> grpcMessingActivity.queryAssignment(context, request)
                .whenComplete((response, throwable) -> writeResponse(context, request, response, responseObserver, throwable,
                    status -> QueryAssignmentResponse.newBuilder().setStatus(status).build())),
            responseObserver,
            QueryAssignmentResponse.newBuilder().setStatus(flowLimitStatus()).build());
    }

    @Override
    public void receiveMessage(ReceiveMessageRequest request, StreamObserver<ReceiveMessageResponse> responseObserver) {
        ProxyContext context = createContext();
        this.addExecutor(this.consumerThreadPoolExecutor,
            context,
            request,
            () -> grpcMessingActivity.receiveMessage(context, request, responseObserver),
            responseObserver,
            ReceiveMessageResponse.newBuilder().setStatus(flowLimitStatus()).build());

    }

    @Override
    public void ackMessage(AckMessageRequest request, StreamObserver<AckMessageResponse> responseObserver) {
        ProxyContext context = createContext();
        this.addExecutor(this.consumerThreadPoolExecutor,
            context,
            request,
            () -> grpcMessingActivity.ackMessage(context, request)
                .whenComplete((response, throwable) -> writeResponse(context, request, response, responseObserver, throwable,
                    status -> AckMessageResponse.newBuilder().setStatus(status).build())),
            responseObserver,
            AckMessageResponse.newBuilder().setStatus(flowLimitStatus()).build());

    }

    @Override
    public void forwardMessageToDeadLetterQueue(ForwardMessageToDeadLetterQueueRequest request,
        StreamObserver<ForwardMessageToDeadLetterQueueResponse> responseObserver) {
        ProxyContext context = createContext();
        this.addExecutor(this.producerThreadPoolExecutor,
            context,
            request,
            () -> grpcMessingActivity.forwardMessageToDeadLetterQueue(context, request)
                .whenComplete((response, throwable) -> writeResponse(context, request, response, responseObserver, throwable,
                    status -> ForwardMessageToDeadLetterQueueResponse.newBuilder().setStatus(status).build())),
            responseObserver,
            ForwardMessageToDeadLetterQueueResponse.newBuilder().setStatus(flowLimitStatus()).build());
    }

    @Override
    public void endTransaction(EndTransactionRequest request, StreamObserver<EndTransactionResponse> responseObserver) {
        ProxyContext context = createContext();
        this.addExecutor(this.transactionThreadPoolExecutor,
            context,
            request,
            () -> grpcMessingActivity.endTransaction(context, request)
                .whenComplete((response, throwable) -> writeResponse(context, request, response, responseObserver, throwable,
                    status -> EndTransactionResponse.newBuilder().setStatus(status).build())),
            responseObserver,
            EndTransactionResponse.newBuilder().setStatus(flowLimitStatus()).build());
    }

    @Override
    public void notifyClientTermination(NotifyClientTerminationRequest request,
        StreamObserver<NotifyClientTerminationResponse> responseObserver) {
        ProxyContext context = createContext();
        this.addExecutor(this.clientManagerThreadPoolExecutor,
            context,
            request,
            () -> grpcMessingActivity.notifyClientTermination(context, request)
                .whenComplete((response, throwable) -> writeResponse(context, request, response, responseObserver, throwable,
                    status -> NotifyClientTerminationResponse.newBuilder().setStatus(status).build())),
            responseObserver,
            NotifyClientTerminationResponse.newBuilder().setStatus(flowLimitStatus()).build());

    }

    @Override
    public void changeInvisibleDuration(ChangeInvisibleDurationRequest request,
        StreamObserver<ChangeInvisibleDurationResponse> responseObserver) {
        ProxyContext context = createContext();
        this.addExecutor(this.consumerThreadPoolExecutor,
            context,
            request,
            () -> grpcMessingActivity.changeInvisibleDuration(context, request)
                .whenComplete((response, throwable) -> writeResponse(context, request, response, responseObserver, throwable,
                    status -> ChangeInvisibleDurationResponse.newBuilder().setStatus(status).build())),
            responseObserver,
            ChangeInvisibleDurationResponse.newBuilder().setStatus(flowLimitStatus()).build());

    }

    @Override
    public StreamObserver<TelemetryCommand> telemetry(StreamObserver<TelemetryCommand> responseObserver) {
        ProxyContext context = createContext();
        StreamObserver<TelemetryCommand> responseTelemetryCommand = grpcMessingActivity.telemetry(context, responseObserver);
        return new StreamObserver<TelemetryCommand>() {
            @Override
            public void onNext(TelemetryCommand value) {
                addExecutor(clientManagerThreadPoolExecutor,
                    context,
                    value,
                    () -> responseTelemetryCommand.onNext(value),
                    responseObserver,
                    TelemetryCommand.newBuilder().setStatus(flowLimitStatus()).build());
            }

            @Override
            public void onError(Throwable t) {
                responseTelemetryCommand.onError(t);
            }

            @Override
            public void onCompleted() {
                responseTelemetryCommand.onCompleted();
            }
        };
    }

    @Override
    public void shutdown() throws Exception {
        this.grpcMessingActivity.shutdown();

        this.routeThreadPoolExecutor.shutdown();
        this.routeThreadPoolExecutor.shutdown();
        this.producerThreadPoolExecutor.shutdown();
        this.consumerThreadPoolExecutor.shutdown();
        this.clientManagerThreadPoolExecutor.shutdown();
        this.transactionThreadPoolExecutor.shutdown();
    }

    @Override
    public void start() throws Exception {
        this.grpcMessingActivity.start();
    }

    protected static class GrpcTask<V, T> implements Runnable {

        protected final Runnable runnable;
        protected final V request;
        protected final T executeRejectResponse;
        protected final StreamObserver<T> streamObserver;

        public GrpcTask(Runnable runnable, V request, StreamObserver<T> streamObserver, T executeRejectResponse) {
            this.runnable = runnable;
            this.streamObserver = streamObserver;
            this.request = request;
            this.executeRejectResponse = executeRejectResponse;
        }

        @Override
        public void run() {
            this.runnable.run();
        }
    }

    protected static class GrpcTaskRejectedExecutionHandler implements RejectedExecutionHandler {

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            if (r instanceof GrpcTask) {
                try {
                    GrpcTask grpcTask = (GrpcTask) r;
                    ResponseWriter.write(grpcTask.streamObserver, grpcTask.executeRejectResponse);
                } catch (Throwable t) {
                    log.warn("write rejected error response failed", t);
                }
            }
        }
    }
}
