# IRSA: each Kubernetes service account assumes its own IAM role through the cluster's OIDC provider, with only
# what its code calls.

locals {
  irsa = {
    api = {
      role_name       = "${local.name}-api"
      namespace       = var.k8s_namespace
      service_account = "swiftcart-api"
      policy          = data.aws_iam_policy_document.api.json
    }
    worker = {
      role_name       = "${local.name}-worker"
      namespace       = var.k8s_namespace
      service_account = "swiftcart-worker"
      policy          = data.aws_iam_policy_document.worker.json
    }
    keda = {
      role_name       = "${local.name}-keda-operator"
      namespace       = "keda"
      service_account = "keda-operator"
      policy          = data.aws_iam_policy_document.keda.json
    }
    external_secrets = {
      role_name       = "${local.name}-external-secrets"
      namespace       = "external-secrets"
      service_account = "external-secrets"
      policy          = data.aws_iam_policy_document.external_secrets.json
    }
  }
}

# API: the outbox relay publishes fulfilment events.
data "aws_iam_policy_document" "api" {
  statement {
    actions   = ["sqs:SendMessage", "sqs:GetQueueAttributes"]
    resources = [aws_sqs_queue.fulfilment.arn]
  }
}

# Worker: consumes fulfilment events and sends order emails.
data "aws_iam_policy_document" "worker" {
  statement {
    actions = [
      "sqs:ReceiveMessage",
      "sqs:DeleteMessage",
      "sqs:ChangeMessageVisibility",
      "sqs:GetQueueAttributes",
    ]
    resources = [aws_sqs_queue.fulfilment.arn]
  }

  statement {
    actions   = ["ses:SendEmail"]
    resources = ["*"]
    condition {
      test     = "StringEquals"
      variable = "ses:FromAddress"
      values   = [var.ses_from_address == "" ? "orders@example.com" : var.ses_from_address]
    }
  }
}

# KEDA operator: reads queue depth to scale workers from zero.
data "aws_iam_policy_document" "keda" {
  statement {
    actions   = ["sqs:GetQueueAttributes"]
    resources = [aws_sqs_queue.fulfilment.arn]
  }
}

data "aws_iam_policy_document" "external_secrets" {
  statement {
    actions   = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
    resources = [aws_secretsmanager_secret.app.arn]
  }

  statement {
    actions   = ["kms:Decrypt"]
    resources = [aws_kms_key.platform.arn]
  }
}

data "aws_iam_policy_document" "irsa_trust" {
  for_each = local.irsa

  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [module.eks.oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "${module.eks.oidc_provider}:sub"
      values   = ["system:serviceaccount:${each.value.namespace}:${each.value.service_account}"]
    }

    condition {
      test     = "StringEquals"
      variable = "${module.eks.oidc_provider}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "irsa" {
  for_each = local.irsa

  name               = each.value.role_name
  assume_role_policy = data.aws_iam_policy_document.irsa_trust[each.key].json
}

resource "aws_iam_role_policy" "irsa" {
  for_each = local.irsa

  name   = "access"
  role   = aws_iam_role.irsa[each.key].id
  policy = each.value.policy
}

module "lb_controller_irsa" {
  source  = "terraform-aws-modules/iam/aws//modules/iam-role-for-service-accounts-eks"
  version = "~> 5.48"

  role_name                              = "${local.name}-aws-load-balancer-controller"
  attach_load_balancer_controller_policy = true

  oidc_providers = {
    main = {
      provider_arn               = module.eks.oidc_provider_arn
      namespace_service_accounts = ["kube-system:aws-load-balancer-controller"]
    }
  }
}
