resource "aws_kms_key" "platform" {
  description             = "${local.name} data at rest (Aurora, ElastiCache, Secrets Manager)"
  enable_key_rotation     = true
  deletion_window_in_days = 30
}

resource "aws_kms_alias" "platform" {
  name          = "alias/${local.name}-platform"
  target_key_id = aws_kms_key.platform.key_id
}

# --- Aurora PostgreSQL: the order log --------------------------------------------------------------------------

resource "aws_security_group" "aurora" {
  name        = "${local.name}-aurora"
  description = "Postgres from EKS nodes and pods only"
  vpc_id      = module.vpc.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "aurora_from_eks" {
  security_group_id            = aws_security_group.aurora.id
  referenced_security_group_id = module.eks.node_security_group_id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  description                  = "API and worker pods"
}

resource "aws_rds_cluster_parameter_group" "postgres" {
  name        = "${local.name}-aurora-pg16"
  family      = "aurora-postgresql16"
  description = "TLS only, slow-query logging"

  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }

  parameter {
    name  = "log_min_duration_statement"
    value = "250"
  }
}

module "aurora" {
  source = "./modules/aurora-cluster"

  identifier           = "${local.name}-orders"
  engine_version       = var.aurora_engine_version
  min_acu              = var.aurora_min_acu
  max_acu              = var.aurora_max_acu
  reader_count         = var.aurora_reader_count
  db_subnet_group_name = module.vpc.database_subnet_group_name
  security_group_ids   = [aws_security_group.aurora.id]
  parameter_group_name = aws_rds_cluster_parameter_group.postgres.name
  kms_key_arn          = aws_kms_key.platform.arn
}

# --- ElastiCache Redis: live stock, holds, the waiting room -----------------------------------------------------

resource "aws_security_group" "redis" {
  name        = "${local.name}-redis"
  description = "Redis from EKS nodes and pods only"
  vpc_id      = module.vpc.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "redis_from_eks" {
  security_group_id            = aws_security_group.redis.id
  referenced_security_group_id = module.eks.node_security_group_id
  ip_protocol                  = "tcp"
  from_port                    = 6379
  to_port                      = 6379
  description                  = "API pods"
}

# Inventory must never be evicted to make room for something else: with noeviction Redis refuses writes when
# full (checkout answers 503) instead of silently dropping stock counters.
resource "aws_elasticache_parameter_group" "redis" {
  name   = "${local.name}-redis7"
  family = "redis7"

  parameter {
    name  = "maxmemory-policy"
    value = "noeviction"
  }
}

resource "random_password" "redis" {
  length  = 48
  special = false
}

resource "aws_elasticache_replication_group" "redis" {
  replication_group_id = "${local.name}-redis"
  description          = "Drop inventory, reservations and the waiting room"

  engine               = "redis"
  engine_version       = "7.1"
  parameter_group_name = aws_elasticache_parameter_group.redis.name
  node_type            = var.redis_node_type
  port                 = 6379

  # a replica in another AZ takes over automatically if the primary dies mid-drop; anything the replica missed
  # is rebuilt from the order log (POST /api/admin/drops/{id}/rebuild-inventory, or re-arming the drop)
  num_cache_clusters         = 1 + var.redis_replicas
  automatic_failover_enabled = true
  multi_az_enabled           = true

  subnet_group_name  = module.vpc.elasticache_subnet_group_name
  security_group_ids = [aws_security_group.redis.id]

  at_rest_encryption_enabled = true
  kms_key_id                 = aws_kms_key.platform.arn
  transit_encryption_enabled = true
  auth_token                 = random_password.redis.result

  snapshot_retention_limit = 3
  snapshot_window          = "21:00-22:00"
}

resource "aws_cloudwatch_metric_alarm" "redis_memory" {
  alarm_name          = "${local.name}-redis-memory-high"
  alarm_description   = "Redis is filling up; with noeviction, writes (checkouts) fail when it's full"
  namespace           = "AWS/ElastiCache"
  metric_name         = "DatabaseMemoryUsagePercentage"
  dimensions          = { ReplicationGroupId = aws_elasticache_replication_group.redis.id }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 3
  threshold           = 75
  comparison_operator = "GreaterThanThreshold"
  alarm_actions       = var.alarm_topic_arn == "" ? [] : [var.alarm_topic_arn]
}

# --- SQS FIFO: fulfilment events, in order per order ------------------------------------------------------------

resource "aws_sqs_queue" "fulfilment_dlq" {
  name                      = "${local.name}-fulfilment-dlq.fifo"
  fifo_queue                = true
  message_retention_seconds = 1209600
  sqs_managed_sse_enabled   = true
}

resource "aws_sqs_queue" "fulfilment" {
  name                        = "${local.name}-fulfilment.fifo"
  fifo_queue                  = true
  deduplication_scope         = "messageGroup"
  fifo_throughput_limit       = "perMessageGroupId"
  visibility_timeout_seconds  = 60
  receive_wait_time_seconds   = 20
  message_retention_seconds   = 345600
  sqs_managed_sse_enabled     = true
  content_based_deduplication = false # the outbox sends its event id as the deduplication id

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.fulfilment_dlq.arn
    maxReceiveCount     = var.fulfilment_max_receives
  })
}

resource "aws_sqs_queue_redrive_allow_policy" "fulfilment_dlq" {
  queue_url = aws_sqs_queue.fulfilment_dlq.id

  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.fulfilment.arn]
  })
}

resource "aws_cloudwatch_metric_alarm" "fulfilment_dlq" {
  alarm_name          = "${local.name}-fulfilment-dlq-not-empty"
  alarm_description   = "A paid order's fulfilment (invoice, email, warehouse, refund) keeps failing"
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = aws_sqs_queue.fulfilment_dlq.name }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_topic_arn == "" ? [] : [var.alarm_topic_arn]
}

# --- S3: the storefront's static assets, served only through CloudFront --------------------------------------------

resource "aws_s3_bucket" "assets" {
  bucket = "${local.name}-assets-${data.aws_caller_identity.current.account_id}"
}

resource "aws_s3_bucket_public_access_block" "assets" {
  bucket                  = aws_s3_bucket.assets.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "assets" {
  bucket = aws_s3_bucket.assets.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "assets" {
  bucket = aws_s3_bucket.assets.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

data "aws_iam_policy_document" "assets" {
  statement {
    sid       = "CloudFrontOnly"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.assets.arn}/*"]
    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.shop.arn]
    }
  }
}

resource "aws_s3_bucket_policy" "assets" {
  bucket     = aws_s3_bucket.assets.id
  policy     = data.aws_iam_policy_document.assets.json
  depends_on = [aws_s3_bucket_public_access_block.assets]
}
