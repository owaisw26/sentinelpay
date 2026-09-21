package com.sentinelpay.payments.config;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration
public class SqsConfig {
    @Bean
    @Profile({"local", "test"})
    public SqsClient localSqsClient(
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

    @Bean
    @Profile({"local", "test"})
    public SnsClient localSnsClient(
        @Value("${sentinelpay.aws.endpoint:http://localhost:4566}") URI endpoint,
        @Value("${sentinelpay.aws.region:ap-southeast-2}") String region
    ) {
        return SnsClient.builder()
            .endpointOverride(endpoint)
            .region(Region.of(region))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("test", "test")
                )
            )
            .build();
    }

    @Bean
    @Profile("cloud")
    public SqsClient cloudSqsClient(
        @Value("${sentinelpay.aws.region:ap-southeast-2}") String region
    ) {
        return SqsClient.builder()
            .region(Region.of(region))
            .build();
    }

    @Bean
    @Profile("cloud")
    public SnsClient cloudSnsClient(
        @Value("${sentinelpay.aws.region:ap-southeast-2}") String region
    ) {
        return SnsClient.builder()
            .region(Region.of(region))
            .build();
    }
}
