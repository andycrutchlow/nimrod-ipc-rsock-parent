package com.nimrodtechs.ipcrsock.subscriber;

import com.nimrodtechs.ipcrsock.common.MessageReceiverInterface;
import com.nimrodtechs.ipcrsock.common.PublisherPayload;

import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

class MessageProcessorEntry {
    MessageReceiverInterface messageReceiver;
    QueueExecutor queueExecutor;

    AtomicBoolean inProgressIndicator = new AtomicBoolean(false);
    protected Queue<PublisherPayload> messages;

    //protected PublisherPayload[] conflatedMessages;
    MessageProcessorEntry(MessageReceiverInterface messageReceiver, QueueExecutor queueExecutor) {
        this.messageReceiver = messageReceiver;
        this.queueExecutor = queueExecutor;
        if(queueExecutor instanceof SequentialExecutor) {
            messages = new ArrayBlockingQueue<>(QueueExecutor.MAX_QUEUE);
        } else {
            //conflatedMessages = new PublisherPayload[2];
            messages = new ConflatedBlockingQueue<>(2);
        }
    }
    public AtomicBoolean getInProgressIndicator() {
        return inProgressIndicator;
    }

}