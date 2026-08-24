# SentinelPay Architecture

## System Context

```text
Customer
   |
   v
React Dashboard
   |
   v
Payments Service (Spring Boot)
   |
   v
PostgreSQL

Fraud Service (FastAPI)
   |
   +-- Future asynchronous risk processing

LocalStack
   |
   +-- Future SNS/SQS integration