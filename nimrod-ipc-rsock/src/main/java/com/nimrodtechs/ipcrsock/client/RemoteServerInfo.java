package com.nimrodtechs.ipcrsock.client;

import org.apache.commons.pool2.impl.GenericObjectPool;
import org.springframework.messaging.rsocket.RSocketRequester;

public class RemoteServerInfo {

    private String name;
    private String host;
    private int port;
    private int maxConcurrentCalls;
    //Override keepAliveWaitTime with a large number if you expect to use debugger in server side with breakpoints
    private int keepAliveWaitTime = 7200;

    private int keepAliveInterval = 2;
    private int retryReconnectInterval = 0;
    private long retryMaxAttempts = 0;

    protected GenericObjectPool<RSocketRequester> connectionPool;

    public RemoteServerInfo(String name, String host, int port, int maxConcurrentCalls) {
        this.name = name;
        this.host = host;
        this.port = port;
        this.maxConcurrentCalls = maxConcurrentCalls;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getMaxConcurrentCalls() {
        return maxConcurrentCalls;
    }

    public void setMaxConcurrentCalls(int maxConcurrentCalls) {
        this.maxConcurrentCalls = maxConcurrentCalls;
    }

    public int getKeepAliveWaitTime() {
        return keepAliveWaitTime;
    }

    public void setKeepAliveWaitTime(int keepAliveWaitTime) {
        this.keepAliveWaitTime = keepAliveWaitTime;
    }

    public int getKeepAliveInterval() {
        return keepAliveInterval;
    }

    public void setKeepAliveInterval(int keepAliveInterval) {
        this.keepAliveInterval = keepAliveInterval;
    }

    public int getRetryReconnectInterval() {
        return retryReconnectInterval;
    }

    public void setRetryReconnectInterval(int retryReconnectInterval) {
        this.retryReconnectInterval = retryReconnectInterval;
    }

    public long getRetryMaxAttempts() {
        return retryMaxAttempts;
    }

    public void setRetryMaxAttempts(long retryMaxAttempts) {
        this.retryMaxAttempts = retryMaxAttempts;
    }



    public GenericObjectPool<RSocketRequester> getConnectionPool() {
        return connectionPool;
    }

    void setConnectionPool(GenericObjectPool<RSocketRequester> connectionPool) {
        this.connectionPool = connectionPool;
    }
}
