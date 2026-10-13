#!/bin/bash
# LocalStack ready hook: the fulfilment FIFO queue and its dead-letter queue (same shape as infra/terraform).
set -euo pipefail

awslocal sqs create-queue --queue-name swiftcart-fulfilment-dlq.fifo --attributes FifoQueue=true >/dev/null
dlq_arn=$(awslocal sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/swiftcart-fulfilment-dlq.fifo \
  --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)
awslocal sqs create-queue --queue-name swiftcart-fulfilment.fifo \
  --attributes "{\"FifoQueue\":\"true\",\"VisibilityTimeout\":\"60\",\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"${dlq_arn}\\\",\\\"maxReceiveCount\\\":\\\"5\\\"}\"}" >/dev/null

echo "LocalStack ready: swiftcart-fulfilment.fifo (+ DLQ)"
