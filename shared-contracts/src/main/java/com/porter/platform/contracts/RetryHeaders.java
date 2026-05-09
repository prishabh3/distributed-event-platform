package com.porter.platform.contracts;

public final class RetryHeaders {

    public static final String RETRY_COUNT      = "X-Retry-Count";
    public static final String RETRY_TOPIC      = "X-Retry-Topic";
    public static final String SCHEDULED_AT     = "X-Retry-Scheduled-At";
    public static final String ORIGINAL_TOPIC   = "X-Original-Topic";
    public static final String FAILURE_REASON   = "X-Failure-Reason";
    public static final String FAILURE_TIMESTAMP= "X-Failure-Timestamp";

    private RetryHeaders() {}
}
