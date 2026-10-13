# One Aurora PostgreSQL Serverless v2 cluster. Used for the pooled cluster (all POOLED tenants, isolated by RLS)
# and for every dedicated SILO tenant, so both tiers run the same engine, parameters and backups.

terraform {
  required_providers {
    aws = {
      source = "hashicorp/aws"
    }
  }
}

variable "identifier" {
  type = string
}

variable "engine_version" {
  type = string
}

variable "min_acu" {
  type = number
}

variable "max_acu" {
  type = number
}

variable "reader_count" {
  type    = number
  default = 0
}

variable "db_subnet_group_name" {
  type = string
}

variable "security_group_ids" {
  type = list(string)
}

variable "parameter_group_name" {
  type = string
}

variable "kms_key_arn" {
  type = string
}

variable "backup_retention_days" {
  type    = number
  default = 14
}

variable "deletion_protection" {
  type    = bool
  default = true
}

variable "tags" {
  type    = map(string)
  default = {}
}

resource "aws_rds_cluster" "this" {
  cluster_identifier = var.identifier
  engine             = "aurora-postgresql"
  engine_mode        = "provisioned"
  engine_version     = var.engine_version

  # the master password lives only in Secrets Manager, rotated by RDS
  master_username               = "tf_admin"
  manage_master_user_password   = true
  master_user_secret_kms_key_id = var.kms_key_arn

  db_subnet_group_name            = var.db_subnet_group_name
  vpc_security_group_ids          = var.security_group_ids
  db_cluster_parameter_group_name = var.parameter_group_name

  storage_encrypted = true
  kms_key_id        = var.kms_key_arn

  backup_retention_period   = var.backup_retention_days
  preferred_backup_window   = "20:00-21:00"
  copy_tags_to_snapshot     = true
  deletion_protection       = var.deletion_protection
  skip_final_snapshot       = false
  final_snapshot_identifier = "${var.identifier}-final"

  enabled_cloudwatch_logs_exports = ["postgresql"]

  serverlessv2_scaling_configuration {
    min_capacity = var.min_acu
    max_capacity = var.max_acu
  }

  tags = var.tags
}

# instance 0 is the writer; the rest are readers in other AZs and failover targets
resource "aws_rds_cluster_instance" "this" {
  count = 1 + var.reader_count

  identifier         = "${var.identifier}-${count.index}"
  cluster_identifier = aws_rds_cluster.this.id
  instance_class     = "db.serverless"
  engine             = aws_rds_cluster.this.engine
  engine_version     = aws_rds_cluster.this.engine_version

  performance_insights_enabled    = true
  performance_insights_kms_key_id = var.kms_key_arn
  auto_minor_version_upgrade      = true

  tags = var.tags
}

output "cluster_id" {
  value = aws_rds_cluster.this.id
}

output "endpoint" {
  value = aws_rds_cluster.this.endpoint
}

output "reader_endpoint" {
  value = aws_rds_cluster.this.reader_endpoint
}

output "port" {
  value = aws_rds_cluster.this.port
}

output "master_secret_arn" {
  value = aws_rds_cluster.this.master_user_secret[0].secret_arn
}
