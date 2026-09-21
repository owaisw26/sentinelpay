package com.sentinelpay.payments;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.CreateTopicRequest;
import software.amazon.awssdk.services.sns.model.SetSubscriptionAttributesRequest;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.SetQueueAttributesRequest;

@ActiveProfiles("test")
abstract class AbstractIntegrationTest {
    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("sentinelpay_test")
            .withUsername("sentinelpay")
            .withPassword("sentinelpay_test");

    private static final LocalStackContainer LOCALSTACK =
        new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:4.14.0")
        ).withServices(
            LocalStackContainer.Service.SNS,
            LocalStackContainer.Service.SQS
        );

    private static String paymentQueueUrl;
    private static String paymentTopicArn;

    static {
        Startables.deepStart(Stream.of(POSTGRES, LOCALSTACK)).join();
        paymentQueueUrl = createPaymentQueue("payment-events");
        paymentTopicArn = createPaymentTopic(
            "payment-events", paymentQueueUrl
        );
    }

    @DynamicPropertySource
    static void registerInfrastructure(DynamicPropertyRegistry registry) {
        registerInfrastructure(
            registry,
            AbstractIntegrationTest::paymentQueueUrl,
            AbstractIntegrationTest::paymentTopicArn
        );
    }

    protected static void registerInfrastructure(
        DynamicPropertyRegistry registry,
        Supplier<Object> queueUrl,
        Supplier<Object> topicArn
    ) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add(
            "sentinelpay.aws.endpoint",
            () -> LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.SQS)
        );
        registry.add("sentinelpay.aws.region", LOCALSTACK::getRegion);
        registry.add(
            "sentinelpay.sqs.payment-events-url",
            queueUrl
        );
        registry.add(
            "sentinelpay.sns.payment-events-topic-arn",
            topicArn
        );
    }

    protected static String paymentQueueUrl() {
        return paymentQueueUrl;
    }

    protected static String paymentTopicArn() {
        return paymentTopicArn;
    }

    protected static SqsClient newSqsClient() {
        return SqsClient.builder()
            .endpointOverride(
                LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.SQS)
            )
            .region(Region.of(LOCALSTACK.getRegion()))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(
                        LOCALSTACK.getAccessKey(),
                        LOCALSTACK.getSecretKey()
                    )
                )
            )
            .build();
    }

    protected static SnsClient newSnsClient() {
        return SnsClient.builder()
            .endpointOverride(
                LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.SNS)
            )
            .region(Region.of(LOCALSTACK.getRegion()))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(
                        LOCALSTACK.getAccessKey(),
                        LOCALSTACK.getSecretKey()
                    )
                )
            )
            .build();
    }

    protected static String createPaymentQueue(String queueNamePrefix) {
        String queueSuffix = UUID.randomUUID().toString();
        try (SqsClient sqs = newSqsClient()) {
            String dlqUrl = sqs.createQueue(
                CreateQueueRequest.builder()
                    .queueName(queueNamePrefix + "-dlq-" + queueSuffix)
                    .build()
            ).queueUrl();

            String dlqArn = sqs.getQueueAttributes(
                GetQueueAttributesRequest.builder()
                    .queueUrl(dlqUrl)
                    .attributeNames(QueueAttributeName.QUEUE_ARN)
                    .build()
            ).attributes().get(QueueAttributeName.QUEUE_ARN);

            String redrivePolicy = """
                {"deadLetterTargetArn":"%s","maxReceiveCount":"5"}
                """.formatted(dlqArn).trim();

            return sqs.createQueue(
                CreateQueueRequest.builder()
                    .queueName(queueNamePrefix + "-" + queueSuffix)
                    .attributes(Map.of(
                        QueueAttributeName.REDRIVE_POLICY,
                        redrivePolicy,
                        QueueAttributeName.VISIBILITY_TIMEOUT,
                        "30"
                    ))
                    .build()
            ).queueUrl();
        }
    }

    protected static String createPaymentTopic(
        String topicNamePrefix,
        String queueUrl
    ) {
        String suffix = UUID.randomUUID().toString();
        try (
            SqsClient sqs = newSqsClient();
            SnsClient sns = newSnsClient()
        ) {
            String queueArn = sqs.getQueueAttributes(
                GetQueueAttributesRequest.builder()
                    .queueUrl(queueUrl)
                    .attributeNames(QueueAttributeName.QUEUE_ARN)
                    .build()
            ).attributes().get(QueueAttributeName.QUEUE_ARN);
            String topicArn = sns.createTopic(
                CreateTopicRequest.builder()
                    .name(topicNamePrefix + "-" + suffix)
                    .build()
            ).topicArn();
            String queuePolicy = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",\
                "Principal":{"Service":"sns.amazonaws.com"},\
                "Action":"sqs:SendMessage","Resource":"%s",\
                "Condition":{"ArnEquals":{"aws:SourceArn":"%s"}}}]}
                """.formatted(queueArn, topicArn).replace("\n", "");
            sqs.setQueueAttributes(
                SetQueueAttributesRequest.builder()
                    .queueUrl(queueUrl)
                    .attributes(Map.of(QueueAttributeName.POLICY, queuePolicy))
                    .build()
            );
            String subscriptionArn = sns.subscribe(
                SubscribeRequest.builder()
                    .topicArn(topicArn)
                    .protocol("sqs")
                    .endpoint(queueArn)
                    .attributes(Map.of("RawMessageDelivery", "true"))
                    .returnSubscriptionArn(true)
                    .build()
            ).subscriptionArn();
            sns.setSubscriptionAttributes(
                SetSubscriptionAttributesRequest.builder()
                    .subscriptionArn(subscriptionArn)
                    .attributeName("FilterPolicy")
                    .attributeValue(
                        "{\"eventType\":[\"PAYMENT_CREATED\",\"PAYMENT_APPROVED\"]}"
                    )
                    .build()
            );
            return topicArn;
        }
    }
}
