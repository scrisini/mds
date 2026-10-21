package com.mds.service;

public class StreamCapacityException extends RuntimeException {

    public StreamCapacityException(int maxSubscribers) {
        super("Stream subscriber limit of %d reached; retry later".formatted(maxSubscribers));
    }
}
