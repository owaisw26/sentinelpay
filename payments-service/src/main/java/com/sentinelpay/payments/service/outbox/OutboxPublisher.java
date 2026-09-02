package com.sentinelpay.payments.service.outbox;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.sentinelpay.payments.domain.OutboxEvent;
import com.sentinelpay.payments.repository.OutboxEventRepository;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import tools.jackson.databind.ObjectMapper;

@Service
public class OutboxPublisher {
    private final OutboxEventRepository outboxEventRepository;
    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;
    private final String queueUrl;

    public OutboxPublisher(
        OutboxEventRepository outboxEventRepository,
        SqsClient sqsClient,
        ObjectMapper objectMapper,
        @Value("${sentinelpay.sqs.payment-events-url}") String queueUrl
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.sqsClient = sqsClient;
        this.objectMapper = objectMapper;
        this.queueUrl = queueUrl;
    }

    @Scheduled(fixedDelayString = "${sentinelpay.outbox.poll-ms:1000}")
    public void publishPendingEvents() {
        publishBatch();
    }

    public void publishBatch() {
        List<OutboxEvent> events = outboxEventRepository.findByPublishedAtIsNull();

        for (OutboxEvent event: events) {
            OutboxMessage message = new OutboxMessage(event.getId(), event.getAggregateId(), event.getEventType(), event.getCorrelationId(), event.getCreatedAt(), event.getPayload());

            String messageBody = objectMapper.writeValueAsString(message);

            SendMessageRequest request = SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(messageBody)
                .build();

            sqsClient.sendMessage(request);

            event.markPublished();
            outboxEventRepository.save(event);
        }
    }
}
