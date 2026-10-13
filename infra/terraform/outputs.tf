output "region" {
  value = var.region
}

output "cluster_name" {
  value = module.eks.cluster_name
}

output "configure_kubectl" {
  value = "aws eks update-kubeconfig --region ${var.region} --name ${module.eks.cluster_name}"
}

output "aurora_endpoint" {
  value = module.aurora.endpoint
}

output "aurora_master_secret_arn" {
  description = "Master credentials, used once by infra/scripts/bootstrap-db.sh, never by the app."
  value       = module.aurora.master_secret_arn
}

output "redis_endpoint" {
  value = aws_elasticache_replication_group.redis.primary_endpoint_address
}

output "fulfilment_queue_url" {
  value = aws_sqs_queue.fulfilment.url
}

output "fulfilment_dlq_url" {
  value = aws_sqs_queue.fulfilment_dlq.url
}

output "assets_bucket" {
  description = "Sync the storefront's .next/static to _next/static/ and public/images to images/ on each release."
  value       = aws_s3_bucket.assets.bucket
}

output "cloudfront_domain" {
  value = aws_cloudfront_distribution.shop.domain_name
}

output "waf_acl_arn" {
  description = "For the alb.ingress.kubernetes.io/wafv2-acl-arn annotation: the ALB only accepts CloudFront."
  value       = aws_wafv2_web_acl.origin.arn
}

output "edge_waf_acl_arn" {
  description = "Bot control and per-IP limits, attached to CloudFront."
  value       = aws_wafv2_web_acl.edge.arn
}

output "app_secret_name" {
  value = aws_secretsmanager_secret.app.name
}

output "irsa_role_arns" {
  value = merge(
    { for k, role in aws_iam_role.irsa : k => role.arn },
    { aws_load_balancer_controller = module.lb_controller_irsa.iam_role_arn },
  )
}

output "app_config" {
  description = "Values for the swiftcart-config ConfigMap in k8s/overlays/prod."
  value = {
    AWS_REGION           = var.region
    DB_URL               = "jdbc:postgresql://${module.aurora.endpoint}:5432/swiftcart?sslmode=require"
    REDIS_HOST           = aws_elasticache_replication_group.redis.primary_endpoint_address
    REDIS_SSL            = "true"
    FULFILMENT_QUEUE_URL = aws_sqs_queue.fulfilment.url
    SES_FROM_ADDRESS     = var.ses_from_address
  }
}
