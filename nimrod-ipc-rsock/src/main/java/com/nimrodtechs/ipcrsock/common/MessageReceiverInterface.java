package com.nimrodtechs.ipcrsock.common;

public interface MessageReceiverInterface<T> {
    void messageReceived(String publisherName, String subject, T message);
}