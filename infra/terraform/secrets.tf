# One Secrets Manager secret, synced into the cluster by the External Secrets Operator. Nothing secret is in git.

resource "random_password" "db_app" {
  length  = 32
  special = false
}

resource "random_password" "jwt" {
  length  = 64
  special = false
}

resource "random_password" "entry_token" {
  length  = 64
  special = false
}

resource "aws_secretsmanager_secret" "app" {
  name                    = "swiftcart/${var.environment}"
  description             = "SwiftCart runtime secrets"
  kms_key_id              = aws_kms_key.platform.arn
  recovery_window_in_days = 7
}

resource "aws_secretsmanager_secret_version" "app" {
  secret_id = aws_secretsmanager_secret.app.id

  secret_string = jsonencode({
    DB_PASSWORD        = random_password.db_app.result
    REDIS_PASSWORD     = random_password.redis.result
    JWT_SECRET         = random_password.jwt.result
    ENTRY_TOKEN_SECRET = random_password.entry_token.result
    # from the Razorpay dashboard after the first apply (aws secretsmanager put-secret-value ...)
    RAZORPAY_KEY_ID         = "set-me"
    RAZORPAY_KEY_SECRET     = "set-me"
    RAZORPAY_WEBHOOK_SECRET = "set-me"
  })

  # the provider keys are set by hand; don't overwrite them on the next apply
  lifecycle {
    ignore_changes = [secret_string]
  }
}
