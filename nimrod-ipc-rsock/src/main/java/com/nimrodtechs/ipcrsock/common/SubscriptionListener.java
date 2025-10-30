package com.nimrodtechs.ipcrsock.common;

public interface SubscriptionListener {
    void onSubscription(SubscriptionRequest subscriptionRequest);
    void onSubscriptionRemove(SubscriptionRequest subscriptionRequest);
}
