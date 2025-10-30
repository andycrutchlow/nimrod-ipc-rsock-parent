/*
 * Copyright 2014 Andrew Crutchlow
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.nimrodtechs.ipcrsock.subscriber;

import com.nimrodtechs.ipcrsock.common.PublisherPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Generalized executor service that manages threading on a per subject basis.
 * Note : this means messages arriving as result of wildcard subscriptions will
 * be handled by same thread which means the listener needs to handle splitting
 * of concurrent work and queue logic.
 *
 * @author andy
 */
public abstract class QueueExecutor implements Thread.UncaughtExceptionHandler {
    public final static int MAX_QUEUE = 5096;
    final static Logger logger = LoggerFactory.getLogger("QueueExecutor");
    protected int warningThreshold = MAX_QUEUE - 100;
    /**
     * Threads created dynamically as needed. There cannot be more threads than
     * subjects subscribed. There should be a lot less though.
     */
    protected ThreadPoolExecutor serviceThreads;
    int logCount = 0;
    private final String publisherName;
    private final String threadNamePrefix;
    // Different ways of assigning this value..start with a default which can be
    // overriden thru commandline vm args
    private int threadPoolSize = System.getProperty("subscriptionThreadPoolSize") != null ? Integer.parseInt(System.getProperty("subscriptionThreadPoolSize")) : 4;
    private int threadPoolType = 0;

    private boolean doStats = Boolean.parseBoolean(System.getProperty("nimrod.ipc.doStats","false"));

    class TF implements ThreadFactory {
        Thread.UncaughtExceptionHandler handler;
        // Only one thread will ever be invoking the factory so dont need to
        // make count threadsafe...
        // AtomicInteger count = new AtomicInteger(1);
        int count = 1;

        TF(Thread.UncaughtExceptionHandler handler) {
            this.handler = handler;
        }

        public synchronized Thread newThread(Runnable r) {
            Thread t = new Thread(r);
            t.setName(threadNamePrefix + "-" + count++);
            t.setUncaughtExceptionHandler(handler);
            return t;
        }
    }

    public QueueExecutor(String  publisherName) {
        this.publisherName = publisherName;
        this.threadNamePrefix = publisherName+"-"+(this instanceof SequentialExecutor ? "seq":"confl");
        serviceThreads = (ThreadPoolExecutor) Executors.newFixedThreadPool(threadPoolSize, new TF(this));
        logger.info("Initialized subscribe dispatching thread pool for : "+publisherName +" size=" + threadPoolSize+" type = "+(this instanceof SequentialExecutor ? "sequential":"conflating"));
    }

    public void dispose() {
        if (serviceThreads != null)
            serviceThreads.shutdownNow();
    }

    @Override
    public void uncaughtException(Thread thread, Throwable e) {
        logger.error(" thread=" + thread.getName() + " " + e.getMessage(), e);

    }

    abstract public void process(PublisherPayload publisherPayload, MessageProcessorEntry mpe);



    private PublisherPayload getNextMessage(MessageProcessorEntry mpe) {
        if (mpe.queueExecutor instanceof SequentialExecutor) {
            // if(warningThreshold > 0)
            // {
            // if(mpe.messages.size() > warningThreshold)
            // {
            // if(logCount++ % 1000 == 0)
            // //Only log every 1000th time this condition met
            // logger.warn("Queue size is "+mpe.messages.size()+" which is greater than warning threashold "+warningThreshold);
            // }
            // }
            return mpe.messages.poll();
        } else {
            // Conflating queue - so flip between 2 slots ... when both are
            // empty then we are done
//            if (mpe.conflatedMessages[0] != null) {
//                PublisherPayload o = mpe.conflatedMessages[0];
//                mpe.conflatedMessages[0] = null;
//                return o;
//            } else if (mpe.conflatedMessages[1] != null) {
//                PublisherPayload o = mpe.conflatedMessages[1];
//                mpe.conflatedMessages[1] = null;
//                return o;
//            } else
//                return null;
            return mpe.messages.poll();
        }
    }


    /**
     * This ensures messages for the same subject that arrive in short/same
     * period of time get processed in order by the same thread if there is a
     * backlog. A later message cannot over take an earlier one no matter how
     * long a messageReceived method takes.
     *
     * @author andy
     */
    protected final class ServiceMessageTask implements Runnable {
        MessageProcessorEntry mpe;

        ServiceMessageTask(MessageProcessorEntry mpe) {
            this.mpe = mpe;
        }

        /**
         * This thread is dedicated to processing all messages for a particular
         * subject for a period of time whilst there are current messages to be
         * consumed for the subject
         */
        @Override
        public void run() {
            PublisherPayload publisherPayload;
            // When there is no more outstanding work then exit this thread
            try {
                while ((publisherPayload = getNextMessage(mpe)) != null) {
                    mpe.messageReceiver.messageReceived(publisherName, publisherPayload.getSubject(), publisherPayload.getPayload());
                }
            } finally {
                mpe.getInProgressIndicator().set(false);
            }
        }
    }

}
