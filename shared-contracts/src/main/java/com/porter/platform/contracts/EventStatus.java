package com.porter.platform.contracts;

public enum EventStatus {
    PENDING,
    QUEUED,
    PROCESSING,
    DELIVERED,
    FAILED,
    DEAD_LETTERED
}
