package com.nimrodtechs.ipcrsock.client;

import com.nimrodtechs.ipcrsock.common.NimrodRmiException;
import io.rsocket.transport.netty.client.TcpClientTransport;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.messaging.rsocket.RSocketStrategies;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class RemoteServerService {
    private static final Logger log = LoggerFactory.getLogger(RemoteServerService.class);
    @Autowired
    RSocketStrategies rSocketStrategies;

    @Autowired
    RemoteServerProperties remoteServerProperties;

    boolean forwardingMode = false;
    public void setForwardingMode(boolean forwardingMode) {
        this.forwardingMode = forwardingMode;
    }


    private Map<String, RemoteServerInfo> remoteServerInfoMap = new HashMap<>();


    @PostConstruct
    void init() throws Exception {
        if(remoteServerProperties.getSetup() == null) {
            //Quietly return and don't attempt to set up client side -> server connection pools
            return;
        }
        for (String remoteServerInfoItems : remoteServerProperties.getSetup()) {
            String[] items = remoteServerInfoItems.split(",");
            RemoteServerInfo remoteServerInfo = new RemoteServerInfo(items[0], items[1], Integer.parseInt(items[2]), Integer.parseInt(items[3]));
            //Optional extra settings
            if (items.length > 4) {
                //Set this with a large number if you expect to use debugger in server side with breakpoints
                remoteServerInfo.setKeepAliveWaitTime(Integer.parseInt(items[4]));
            }
            if (items.length > 5) {
                remoteServerInfo.setKeepAliveInterval(Integer.parseInt(items[5]));
            }
            if (items.length > 6) {
                remoteServerInfo.setRetryMaxAttempts(Integer.parseInt(items[6]));
            }
            if (items.length > 7) {
                remoteServerInfo.setRetryReconnectInterval(Integer.parseInt(items[7]));
            }
            addRemoteServer(remoteServerInfo);
        }
    }

    /**
     * * This is able to be called programatically to setup new connections on demand
     * @param remoteServerInfo
     * @throws Exception
     */
    public void addRemoteServer(RemoteServerInfo remoteServerInfo) throws Exception {
        remoteServerInfo.setConnectionPool(getConnectPool(remoteServerInfo));
        remoteServerInfoMap.put(remoteServerInfo.getName(), remoteServerInfo);
    }

    private GenericObjectPool<RSocketRequester> getConnectPool(RemoteServerInfo remoteServerInfo) throws Exception {
        GenericObjectPoolConfig poolConfig = new GenericObjectPoolConfig();
        poolConfig.setMaxTotal(remoteServerInfo.getMaxConcurrentCalls());
        poolConfig.setJmxEnabled(false);
        GenericObjectPool<RSocketRequester> pool = new GenericObjectPool<>(new PoolConnectionFactory(remoteServerInfo), poolConfig);
        //Initialize pool now with 4 pooled objects
        for (int i = 0; i < poolConfig.getMaxTotal(); i++) {
            try {
                RSocketRequester myObject = pool.borrowObject();
                pool.returnObject(myObject);
            } catch (Exception ex) {
                log.error("Error during initialization of Remote Server connection to " + remoteServerInfo.getName() + " host:" + remoteServerInfo.getHost() + " port:" + remoteServerInfo.getPort());
                throw ex;
            }
        }
        log.info("Initialized " + remoteServerInfo.getMaxConcurrentCalls() + " connections to server " + remoteServerInfo.getName() + ":" + remoteServerInfo.getHost() + ":" + remoteServerInfo.getPort());
        return pool;
    }

    class PoolConnectionFactory extends BasePooledObjectFactory<RSocketRequester> {
        private final RemoteServerInfo remoteServerInfo;

        public PoolConnectionFactory(RemoteServerInfo remoteServerInfo) {
            this.remoteServerInfo = remoteServerInfo;
        }

        public RSocketRequester create() throws Exception {
            return getRSocketRequester(rSocketStrategies, remoteServerInfo);
        }

        // when an object is returned to the pool,
        @Override
        public void passivateObject(PooledObject<RSocketRequester> rSockReq) {
            //Do nothing
        }

        @Override
        public boolean validateObject(PooledObject<RSocketRequester> rSockReq) {
            if (rSockReq.getObject().isDisposed())
                return false;
            else
                return true;
        }

        @Override
        public void destroyObject(PooledObject<RSocketRequester> rSockReq) {
            log.info("Destroying rSockReq {}",rSockReq);
            rSockReq.getObject().dispose();
        }

        @Override
        public PooledObject<RSocketRequester> wrap(RSocketRequester rSockReq) {
            return new DefaultPooledObject<>(rSockReq);

        }

        // for all other methods, the no-op
        // implementation in BasePoolableObjectFactory
        // will suffice
    }

    /**
     * Kryo is added as a serializer *
     *
     * @return
     */
//    private RSocketStrategies rSocketStrategies() {
//        KryoCommon kryoCommon = new KryoCommon();
//        return RSocketStrategies.builder()
//                .encoders(encoders -> encoders.add(new KryoEncoder(kryoCommon)))
//                .decoders(decoders -> decoders.add(new KryoDecoder(kryoCommon)))
//                .build();
//    }

    private RSocketRequester getRSocketRequester(RSocketStrategies strategies, RemoteServerInfo remoteServerInfo) {
        RSocketRequester.Builder builder = RSocketRequester.builder();
        return builder
                .rsocketConnector(
                        rSocketConnector -> {
                            rSocketConnector.keepAlive(Duration.ofSeconds(remoteServerInfo.getKeepAliveInterval()), Duration.ofSeconds(remoteServerInfo.getKeepAliveWaitTime()));
                            rSocketConnector.reconnect(
                                    Retry.backoff(remoteServerInfo.getRetryMaxAttempts(), Duration.ofSeconds(remoteServerInfo.getRetryReconnectInterval()))
                            );
                        })
                .rsocketStrategies(strategies)
                .dataMimeType(new MimeType("application", "x-kryo"))
                .transport(TcpClientTransport.create(remoteServerInfo.getHost(), remoteServerInfo.getPort()));
                //.tcp(remoteServerInfo.getHost(), remoteServerInfo.getPort());
    }


    public <T> T executeRmiMethod(Class<T> responseClass, String serviceName, String methodName, Object... parameters) throws Exception {
        RemoteServerInfo remoteServerInfo = remoteServerInfoMap.get(serviceName);
        if(remoteServerInfo == null) {
            throw new NimrodRmiException(serviceName+" is not a registered Remote Server");
        }
        RSocketRequester rsReq = null;
        AtomicBoolean resetConnectionPool = new AtomicBoolean(false);
        try {
            //This will block if all(maxConcurrentCalls) of the resource pool rsocketrequestors are currently occupied
            rsReq = remoteServerInfo.connectionPool.borrowObject();
            RSocketRequester.RequestSpec requestSpec = rsReq.route(methodName);
            T response = null;
            if(forwardingMode) {
            } else {
                response = requestSpec.data(parameters).
                        retrieveMono(responseClass)
                        .onErrorResume(e -> {
                            if (e.getClass().getName().equals("reactor.core.Exceptions$RetryExhaustedException")) {
                                // Handle the closed channel condition, possibly by returning a fallback Mono or throwing a custom exception
                                resetConnectionPool.set(true);

                                return Mono.error(new Exception("Channel was closed!"));
                            }
                            // If the error is not the one we're looking for, propagate it unchanged
                            return Mono.error(e);
                        })
                        .block();
            }
            return response;
        } catch (Exception ex) {
            throw ex;
        } finally {
            remoteServerInfo.connectionPool.returnObject(rsReq);
            if(resetConnectionPool.get()){
                remoteServerInfo.connectionPool.clear();
            }
        }
    }

    public void fireAndForget(String serviceName, String methodName, Object... parameters) throws Exception {
        RemoteServerInfo remoteServerInfo = remoteServerInfoMap.get(serviceName);
        if(remoteServerInfo == null) {
            throw new NimrodRmiException(serviceName+" is not a registered Remote Server");
        }
        RSocketRequester rsReq = null;
        AtomicBoolean resetConnectionPool = new AtomicBoolean(false);
        try {
            //This will block if all(maxConcurrentCalls) of the resource pool rsocketrequestors are currently occupied
            rsReq = remoteServerInfo.connectionPool.borrowObject();
            RSocketRequester.RequestSpec requestSpec = rsReq.route(methodName);
            //N.B. if method in the route corresponds to a fire-and-forget method in the server then it won't actually block
            requestSpec.data(parameters).send()
                    .onErrorResume(e -> {
                        if (e.getClass().getName().equals("reactor.core.Exceptions$RetryExhaustedException")) {
                            // Handle the closed channel condition, possibly by returning a fallback Mono or throwing a custom exception
                            resetConnectionPool.set(true);

                            return Mono.error(new Exception("Channel was closed!"));
                        }
                        // If the error is not the one we're looking for, propagate it unchanged
                        return Mono.error(e);
                    })
                    .block();
        } catch (Exception ex) {
            throw ex;
        } finally {
            remoteServerInfo.connectionPool.returnObject(rsReq);
            if(resetConnectionPool.get()){
                remoteServerInfo.connectionPool.clear();
            }
        }
    }

    public boolean isServerConfigured(String serverName) {
        return remoteServerInfoMap.get(serverName) != null;
    }
}