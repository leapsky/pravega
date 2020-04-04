/**
 * Copyright (c) Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.client.netty.impl;

import com.google.common.annotations.VisibleForTesting;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelPromise;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.PromiseCombiner;
import io.netty.util.Recycler;
import io.netty.util.Recycler.Handle;
import io.pravega.common.Exceptions;
import io.pravega.common.Timer;
import io.pravega.shared.metrics.MetricNotifier;
import io.pravega.shared.protocol.netty.Append;
import io.pravega.shared.protocol.netty.AppendBatchSizeTracker;
import io.pravega.shared.protocol.netty.ConnectionFailedException;
import io.pravega.shared.protocol.netty.WireCommand;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import static io.pravega.shared.NameUtils.segmentTags;
import static io.pravega.shared.metrics.ClientMetricKeys.CLIENT_APPEND_LATENCY;

@Slf4j
public class ClientConnectionImpl implements ClientConnection {

    @Getter
    private final String connectionName;
    @Getter
    private final int flowId;
    @VisibleForTesting
    @Getter
    private final FlowHandler nettyHandler;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Semaphore throttle = new Semaphore(AppendBatchSizeTracker.MAX_BATCH_SIZE);

    public ClientConnectionImpl(String connectionName, int flowId, FlowHandler nettyHandler) {
        this.connectionName = connectionName;
        this.flowId = flowId;
        this.nettyHandler = nettyHandler;
    }

    @Override
    public void send(WireCommand cmd) throws ConnectionFailedException {
        checkClientConnectionClosed();
        nettyHandler.setRecentMessage();
        write(cmd);
    }

    @Override
    public void send(Append append) throws ConnectionFailedException {
        Timer timer = new Timer();
        checkClientConnectionClosed();
        nettyHandler.setRecentMessage();
        write(append);
        // Monitoring appends has a performance cost (e.g., split strings); only do that if we configure a metric notifier.
        if (!nettyHandler.getMetricNotifier().equals(MetricNotifier.NO_OP_METRIC_NOTIFIER)) {
            nettyHandler.getMetricNotifier()
                    .updateSuccessMetric(CLIENT_APPEND_LATENCY, segmentTags(append.getSegment(), append.getWriterId().toString()),
                            timer.getElapsedMillis());
        }
    }

    private void write(Append cmd) throws ConnectionFailedException {
        Channel channel = nettyHandler.getChannel();

        // Work around for https://github.com/netty/netty/issues/3246
        channel.eventLoop(WriteInEventLoopCallback.create(channel, cmd));

        Exceptions.handleInterrupted(() -> throttle.acquire(cmd.getDataLength()));
    }
    
    private void write(WireCommand cmd) throws ConnectionFailedException {
        Channel channel = nettyHandler.getChannel();

        // Work around for https://github.com/netty/netty/issues/3246
        channel.eventLoop(WriteInEventLoopCallback.create(channel, cmd));
    }
    
    private static final class WriteInEventLoopCallback implements Runnable {
        private Channel channel;
        private Object cmd;
        private ChannelPromise promise;

        static WriteInEventLoopCallback create(Channel channel, Object cmd) {
            WriteInEventLoopCallback c =  recycler.get();
            c.channel = channel;
            c.cmd = cmd;
            ChannelPromise promise = channel.newPromise();
            promise.addListener((ChannelFutureListener) future -> {
                throttle.release(cmd.getDataLength());
                if (!future.isSuccess()) {
                    future.channel().pipeline().fireExceptionCaught(future.cause());
                    future.channel().close();
                }
            });
            c.promise = promise;
            return c;
        }

        @Override
        public void run() {
            try {
                channel.write(cmd, promise);
            } finally {
                recycle();
            }
        }

        private void recycle() {
            channel = null;
            cmd = null;
            promise = null;
            recyclerHandle.recycle(this);
        }

        private final Handle<WriteInEventLoopCallback> recyclerHandle;

        private WriteInEventLoopCallback(Handle<WriteInEventLoopCallback> recyclerHandle) {
            this.recyclerHandle = recyclerHandle;
        }

        private static final Recycler<WriteInEventLoopCallback> recycler = new Recycler<WriteInEventLoopCallback>() {
            @Override
            protected WriteInEventLoopCallback newObject(Handle<WriteInEventLoopCallback> handle) {
                return new WriteInEventLoopCallback(handle);
            }
        };
    }

    @Override
    public void sendAsync(WireCommand cmd, CompletedCallback callback) {
        Channel channel = null;
        try {
            checkClientConnectionClosed();
            nettyHandler.setRecentMessage();

            channel = nettyHandler.getChannel();
            log.debug("Write and flush message {} on channel {}", cmd, channel);
            channel.writeAndFlush(cmd)
                   .addListener((Future<? super Void> f) -> {
                       if (f.isSuccess()) {
                           callback.complete(null);
                       } else {
                           callback.complete(new ConnectionFailedException(f.cause()));
                       }
                   });
        } catch (ConnectionFailedException cfe) {
            log.debug("ConnectionFailedException observed when attempting to write WireCommand {} ", cmd);
            callback.complete(cfe);
        } catch (Exception e) {
            log.warn("Exception while attempting to write WireCommand {} on netty channel {}", cmd, channel);
            callback.complete(new ConnectionFailedException(e));
        }
    }

    @Override
    public void sendAsync(List<Append> appends, CompletedCallback callback) {
        Channel ch;
        try {
            checkClientConnectionClosed();
            nettyHandler.setRecentMessage();
            ch = nettyHandler.getChannel();
        } catch (ConnectionFailedException e) {
            callback.complete(new ConnectionFailedException("Connection to " + connectionName + " is not established."));
            return;
        }
        PromiseCombiner combiner = new PromiseCombiner();
        for (Append append : appends) {
            combiner.add(ch.write(append));
        }
        ch.flush();
        ChannelPromise promise = ch.newPromise();
        promise.addListener(future -> {
            Throwable cause = future.cause();
            callback.complete(cause == null ? null : new ConnectionFailedException(cause));
        });
        combiner.finish(promise);
    }

    @Override
    public void close() {
        if (!closed.getAndSet(true)) {
            nettyHandler.closeFlow(this);
        }
    }

    private void checkClientConnectionClosed() throws ConnectionFailedException {
        if (closed.get()) {
            log.error("ClientConnection to {} with flow id {} is already closed", connectionName, flowId);
            throw new ConnectionFailedException("Client connection already closed for flow " + flowId);
        }
    }

}
