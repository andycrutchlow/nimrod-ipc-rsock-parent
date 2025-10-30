package com.nimrodtechs.ipcrsock.subscriber;

import java.util.concurrent.ArrayBlockingQueue;

public class ConflatedBlockingQueue<E> extends ArrayBlockingQueue<E> {
    final int capacity;

    public int getConflatedCount() {
        return conflatedCount;
    }

    int conflatedCount;
    public ConflatedBlockingQueue(int capacity) {
        super(capacity);
        this.capacity = capacity;
    }
    @Override
    public synchronized boolean offer(E e) {
        // If the queue has reached its capacity
        if (size() == capacity) {
            // Remove the second element (assuming queue size is 2)
            conflatedCount++;
            super.poll();
        }
        return super.offer(e);
    }
}
