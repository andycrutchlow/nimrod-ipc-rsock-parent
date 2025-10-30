package com.nimrodtechs.ipcrsock.common;

public class PublisherPayload<T> {
    long timestamp;
    String subject;
    T payload;

    public PublisherPayload(){}

    public PublisherPayload(long timestamp, String subject, T payload) {
        this.timestamp = timestamp;
        this.subject = subject;
        this.payload = payload;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getSubject() {
        return subject;
    }

    public T getPayload() {
        return payload;
    }
}
