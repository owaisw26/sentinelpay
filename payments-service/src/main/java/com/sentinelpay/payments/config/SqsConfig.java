package com.sentinelpay.payments.config;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration
public class SqsConfig {
    @Bean
    public SqsClient sqsClient(
        @Value("${sentinelpay.aws.endpoint:http://localhost:4566}") URI endpoint,
        @Value("${sentinelpay.aws.region:ap-southeast-2}") String region
    ) {
        return SqsClient.builder()
            .endpointOverride(endpoint)
            .region(Region.of(region))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("test", "test")
                )
            )
            .build();
    }
}
