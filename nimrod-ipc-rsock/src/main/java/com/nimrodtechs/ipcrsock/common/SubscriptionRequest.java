package com.nimrodtechs.ipcrsock.common;

import java.util.Objects;

public class SubscriptionRequest {
    private SubscriptionDirective subscriptionDirective;
    private String requestor;
    private String subject;
    private Class dataClass = String.class;
    private boolean wildcard = false;
    @Override
    public String toString() {
//        return "SubscriptionRequest{" +
//                "subscriptionDirective=" + subscriptionDirective +
//                ", requestor='" + requestor + '\'' +
//                ", subject='" + subject + '\'' +
//                '}';
        return subject;
    }
    public SubscriptionRequest(){}

    public SubscriptionRequest(SubscriptionDirective subscriptionDirective, String requestor, String subject) {
        this.subscriptionDirective = subscriptionDirective;
        this.requestor = requestor;
        this.subject = subject;
    }

    public SubscriptionRequest(SubscriptionDirective subscriptionDirective, String requestor, String subject, Class dataClass) {
        this.subscriptionDirective = subscriptionDirective;
        this.requestor = requestor;
        this.subject = subject;
        this.dataClass = dataClass;
    }
    public SubscriptionRequest(SubscriptionDirective subscriptionDirective, String requestor, String subject, boolean wildcard) {
        this.subscriptionDirective = subscriptionDirective;
        this.requestor = requestor;
        this.subject = subject;
        this.wildcard = wildcard;
    }

    public SubscriptionDirective getSubscriptionDirective() {
        return subscriptionDirective;
    }

    public String getRequestor() {
        return requestor;
    }

    public String getSubject() {
        return subject;
    }

    public boolean isWildcard(){
        return wildcard;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SubscriptionRequest that = (SubscriptionRequest) o;
        return Objects.equals(requestor, that.requestor) && Objects.equals(subject, that.subject);
    }

    @Override
    public int hashCode() {
        return Objects.hash(requestor, subject);
    }
}
