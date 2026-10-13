variable "region" {
  description = "AWS region for every regional resource."
  type        = string
  default     = "ap-south-1"
}

variable "environment" {
  description = "Environment name, used in tags and resource names."
  type        = string
  default     = "prod"
}

variable "vpc_cidr" {
  description = "CIDR block of the VPC. Split into public, private (EKS) and database subnets across three AZs."
  type        = string
  default     = "10.50.0.0/16"
}

variable "single_nat_gateway" {
  description = "One NAT gateway instead of one per AZ. Cheaper, but egress depends on a single AZ."
  type        = bool
  default     = false
}

variable "cluster_version" {
  description = "EKS Kubernetes version."
  type        = string
  default     = "1.31"
}

variable "node_instance_types" {
  description = "Instance types for the node group. Compute-optimised: checkout is CPU-bound during a drop."
  type        = list(string)
  default     = ["c7i.xlarge", "c6i.xlarge"]
}

variable "node_capacity_type" {
  description = "ON_DEMAND or SPOT. Keep ON_DEMAND for drop days: a spot reclaim mid-drop is the wrong surprise."
  type        = string
  default     = "ON_DEMAND"

  validation {
    condition     = contains(["ON_DEMAND", "SPOT"], var.node_capacity_type)
    error_message = "node_capacity_type must be ON_DEMAND or SPOT."
  }
}

variable "node_min_size" {
  type    = number
  default = 2
}

variable "node_max_size" {
  description = "Room for the pre-scaled checkout pods at the peak of a drop."
  type        = number
  default     = 20
}

variable "cluster_admin_arns" {
  description = "IAM principals (users or roles) that get cluster-admin through EKS access entries."
  type        = list(string)
  default     = []
}

variable "aurora_engine_version" {
  type    = string
  default = "16.4"
}

variable "aurora_min_acu" {
  type    = number
  default = 0.5
}

variable "aurora_max_acu" {
  description = "Order writes peak with the drop; Serverless v2 scales up within seconds."
  type        = number
  default     = 32
}

variable "aurora_reader_count" {
  type    = number
  default = 1
}

variable "redis_node_type" {
  description = "ElastiCache node type. All inventory decrements for a drop hit one shard, so give it CPU headroom."
  type        = string
  default     = "cache.r7g.large"
}

variable "redis_replicas" {
  description = "Replicas for the inventory shard. At least 1 for automatic Multi-AZ failover mid-drop."
  type        = number
  default     = 1

  validation {
    condition     = var.redis_replicas >= 1
    error_message = "Run at least one replica: losing the inventory primary without a failover target stops the drop."
  }
}

variable "fulfilment_max_receives" {
  description = "Deliveries before a fulfilment event moves to the dead-letter queue."
  type        = number
  default     = 5
}

variable "alarm_topic_arn" {
  description = "SNS topic for alarms. Empty: alarms are created without actions."
  type        = string
  default     = ""
}

variable "waf_rate_limit_per_ip" {
  description = "Requests per IP per 5 minutes across the site before blocking."
  type        = number
  default     = 3000
}

variable "waf_queue_join_limit_per_ip" {
  description = "Waiting-room joins per IP per 5 minutes: a bot farming tickets from one address is cut off."
  type        = number
  default     = 100
}

variable "shop_domain" {
  description = "The storefront's hostname, served by CloudFront (e.g. shop.example.com)."
  type        = string
  default     = ""
}

variable "web_origin_domain" {
  description = "Hostname of the ALB (or its alias) that serves the storefront; CloudFront's origin for pages."
  type        = string
  default     = "origin.example.com"
}

variable "cloudfront_certificate_arn" {
  description = "us-east-1 ACM certificate for shop_domain. Empty uses the default CloudFront certificate."
  type        = string
  default     = ""
}

variable "ses_from_address" {
  description = "Verified SES sender for order emails (e.g. orders@shop.example.com)."
  type        = string
  default     = ""
}

variable "k8s_namespace" {
  type    = string
  default = "swiftcart"
}
