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
    public boolean offer(E e) {
        if (!super.offer(e)) {
            conflatedCount++;
            super.poll();
            return super.offer(e);
        }
        return true;
    }
}
