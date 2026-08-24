# Payment State Machine

```text
CREATED
   |
   v
SCREENING
   | \
   |  +--------> BLOCKED
   v
APPROVED
   |
   v
PROCESSING
   | \
   |  +--------> FAILED
   v
SETTLED

Initial meanings
CREATED — payment request has been accepted.
SCREENING — payment is undergoing fraud/payee checks.
APPROVED — payment passed screening.
PROCESSING — money movement is being executed.
SETTLED — payment completed successfully.
FAILED — processing permanently failed.
BLOCKED — screening rejected the payment.