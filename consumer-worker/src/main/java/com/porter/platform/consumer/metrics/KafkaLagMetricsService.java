package com.porter.platform.consumer.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Component
public class KafkaLagMetricsService {

    private static final Logger log = LoggerFactory.getLogger(KafkaLagMetricsService.class);

    private static final List<String> CONSUMER_GROUPS = List.of(
            "notification-consumer-group",
            "retry-1m-consumer-group",
            "retry-5m-consumer-group",
            "retry-30m-consumer-group",
            "dlq-consumer-group"
    );

    private final AdminClient adminClient;
    private final MeterRegistry registry;
    private final Map<String, AtomicLong> lagGauges = new ConcurrentHashMap<>();

    public KafkaLagMetricsService(AdminClient adminClient, MeterRegistry registry) {
        this.adminClient = adminClient;
        this.registry = registry;
        CONSUMER_GROUPS.forEach(this::getOrCreateGauge);
    }

    @Scheduled(fixedDelay = 30_000)
    public void refreshLag() {
        for (String groupId : CONSUMER_GROUPS) {
            try {
                var committed = adminClient.listConsumerGroupOffsets(groupId)
                        .partitionsToOffsetAndMetadata()
                        .get(10, TimeUnit.SECONDS);

                if (committed.isEmpty()) {
                    getOrCreateGauge(groupId).set(0);
                    continue;
                }

                Map<TopicPartition, OffsetSpec> latestRequest = committed.keySet().stream()
                        .collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest()));

                Map<TopicPartition, Long> endOffsets = adminClient.listOffsets(latestRequest)
                        .all()
                        .get(10, TimeUnit.SECONDS)
                        .entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().offset()));

                long totalLag = committed.entrySet().stream()
                        .mapToLong(e -> {
                            Long end = endOffsets.get(e.getKey());
                            if (end == null || e.getValue() == null) return 0L;
                            return Math.max(0, end - e.getValue().offset());
                        })
                        .sum();

                getOrCreateGauge(groupId).set(totalLag);
                log.debug("Kafka lag group={} lag={}", groupId, totalLag);

            } catch (Exception e) {
                log.warn("Failed to fetch Kafka lag for group={}: {}", groupId, e.getMessage());
            }
        }
    }

    private AtomicLong getOrCreateGauge(String groupId) {
        return lagGauges.computeIfAbsent(groupId, id -> {
            AtomicLong counter = new AtomicLong(0);
            Gauge.builder("kafka_consumer_lag", counter, AtomicLong::get)
                    .tag("group", id)
                    .description("Total consumer lag for Kafka consumer group")
                    .register(registry);
            return counter;
        });
    }
}
