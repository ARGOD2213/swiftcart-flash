# --- The edge: CloudFront + AWS WAF in front of everything -------------------------------------------------------
# All public traffic (pages, the /api/sc proxy, sign-in, payment webhooks) enters through CloudFront. The WAF that
# does the real work is attached to CloudFront, because that is where the shopper's own IP is visible: a per-IP
# rule on the ALB behind CloudFront would count CloudFront's edge servers, and one busy edge would get thousands of
# real shoppers blocked together in the middle of a drop.
# The ALB only accepts requests carrying CloudFront's secret origin header, so nobody can go around the edge.

resource "aws_wafv2_web_acl" "edge" {
  provider = aws.us_east_1
  name     = "${local.name}-edge"
  scope    = "CLOUDFRONT"

  default_action {
    allow {}
  }

  # Bots are the drop's real enemy. Razorpay's and Stripe's servers are "bots" too: webhook deliveries are left
  # alone by the bot, common and flood rules (they are verified by HMAC in the app instead).
  rule {
    name     = "aws-bot-control"
    priority = 1

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesBotControlRuleSet"

        managed_rule_group_configs {
          aws_managed_rules_bot_control_rule_set {
            inspection_level = "COMMON"
          }
        }

        scope_down_statement {
          not_statement {
            statement {
              byte_match_statement {
                search_string         = "/webhooks/"
                positional_constraint = "STARTS_WITH"
                field_to_match {
                  uri_path {}
                }
                text_transformation {
                  priority = 0
                  type     = "NONE"
                }
              }
            }
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${local.name}-bots"
      sampled_requests_enabled   = true
    }
  }

  # Large gateway payloads would trip the body-size and XSS body checks, so webhooks are out of scope here too.
  rule {
    name     = "aws-common"
    priority = 2

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesCommonRuleSet"

        scope_down_statement {
          not_statement {
            statement {
              byte_match_statement {
                search_string         = "/webhooks/"
                positional_constraint = "STARTS_WITH"
                field_to_match {
                  uri_path {}
                }
                text_transformation {
                  priority = 0
                  type     = "NONE"
                }
              }
            }
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${local.name}-common"
      sampled_requests_enabled   = true
    }
  }

  # A bot farming waiting-room tickets from one address is cut off. (The app also allows one ticket per account.)
  rule {
    name     = "queue-join-per-ip"
    priority = 3

    action {
      block {}
    }

    statement {
      rate_based_statement {
        limit              = var.waf_queue_join_limit_per_ip
        aggregate_key_type = "IP"

        scope_down_statement {
          byte_match_statement {
            search_string         = "/queue"
            positional_constraint = "ENDS_WITH"
            field_to_match {
              uri_path {}
            }
            text_transformation {
              priority = 0
              type     = "NONE"
            }
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${local.name}-queue-join"
      sampled_requests_enabled   = true
    }
  }

  # Site-wide flood guard. Gateways retry webhooks in bursts after an outage, so they don't count against it.
  rule {
    name     = "per-ip-flood"
    priority = 4

    action {
      block {}
    }

    statement {
      rate_based_statement {
        limit              = var.waf_rate_limit_per_ip
        aggregate_key_type = "IP"

        scope_down_statement {
          not_statement {
            statement {
              byte_match_statement {
                search_string         = "/webhooks/"
                positional_constraint = "STARTS_WITH"
                field_to_match {
                  uri_path {}
                }
                text_transformation {
                  priority = 0
                  type     = "NONE"
                }
              }
            }
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${local.name}-per-ip"
      sampled_requests_enabled   = true
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "${local.name}-edge"
    sampled_requests_enabled   = true
  }
}

# --- The ALB accepts CloudFront only --------------------------------------------------------------------------------
# CloudFront adds a secret header on its way to the origin; the ALB's WAF lets through only requests that carry it.
# To rotate: taint random_password.origin_verify and apply (CloudFront and the WAF change in the same apply; expect a
# few seconds of 403s at the ALB while CloudFront's config propagates).

resource "random_password" "origin_verify" {
  length  = 48
  special = false
}

resource "aws_wafv2_web_acl" "origin" {
  name  = "${local.name}-origin"
  scope = "REGIONAL"

  default_action {
    block {}
  }

  rule {
    name     = "from-cloudfront"
    priority = 1

    action {
      allow {}
    }

    statement {
      byte_match_statement {
        search_string         = random_password.origin_verify.result
        positional_constraint = "EXACTLY"
        field_to_match {
          single_header {
            name = "x-origin-verify"
          }
        }
        text_transformation {
          priority = 0
          type     = "NONE"
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${local.name}-from-cloudfront"
      sampled_requests_enabled   = false
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "${local.name}-origin"
    sampled_requests_enabled   = false
  }
}

# --- CloudFront --------------------------------------------------------------------------------------------------
# Static assets from S3, product pages cached for as long as the origin says (ISR: 30 s), live stock for 1 s,
# and everything personal (the line, checkout, orders, sign-in, webhooks) passed straight through, never cached.

data "aws_cloudfront_cache_policy" "optimized" {
  name = "Managed-CachingOptimized"
}

data "aws_cloudfront_cache_policy" "disabled" {
  name = "Managed-CachingDisabled"
}

# Every viewer header, cookie and query string goes to the origin, except Host: the ALB routes on the origin's own
# hostname, and CloudFront checks the ALB's certificate against it.
data "aws_cloudfront_origin_request_policy" "all_viewer_except_host" {
  name = "Managed-AllViewerExceptHostHeader"
}

# Honour the origin's Cache-Control (s-maxage), with no cookies in the cache key. Next.js answers the same URL with
# either HTML or an RSC payload (client-side navigation) and relies on `Vary`, which CloudFront ignores, so the
# headers and the `_rsc` parameter that tell them apart are part of the key.
resource "aws_cloudfront_cache_policy" "origin_controlled" {
  name        = "${local.name}-origin-controlled"
  min_ttl     = 0
  default_ttl = 0
  max_ttl     = 300

  parameters_in_cache_key_and_forwarded_to_origin {
    enable_accept_encoding_gzip   = true
    enable_accept_encoding_brotli = true
    cookies_config {
      cookie_behavior = "none"
    }
    headers_config {
      header_behavior = "whitelist"
      headers {
        items = ["RSC", "Next-Router-Prefetch", "Next-Router-Segment-Prefetch"]
      }
    }
    query_strings_config {
      query_string_behavior = "whitelist"
      query_strings {
        items = ["_rsc"]
      }
    }
  }
}

resource "aws_cloudfront_origin_access_control" "assets" {
  name                              = "${local.name}-assets"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

resource "aws_cloudfront_distribution" "shop" {
  enabled         = true
  is_ipv6_enabled = true
  comment         = "${local.name} storefront"
  aliases         = var.shop_domain == "" ? [] : [var.shop_domain]
  price_class     = "PriceClass_200"
  http_version    = "http2and3"
  web_acl_id      = aws_wafv2_web_acl.edge.arn

  origin {
    origin_id   = "web"
    domain_name = var.web_origin_domain

    custom_origin_config {
      http_port              = 80
      https_port             = 443
      origin_protocol_policy = "https-only"
      origin_ssl_protocols   = ["TLSv1.2"]
    }

    custom_header {
      name  = "X-Origin-Verify"
      value = random_password.origin_verify.result
    }
  }

  origin {
    origin_id                = "assets"
    domain_name              = aws_s3_bucket.assets.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.assets.id
  }

  # product and listing pages: cached per the origin's s-maxage
  default_cache_behavior {
    target_origin_id         = "web"
    viewer_protocol_policy   = "redirect-to-https"
    allowed_methods          = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods           = ["GET", "HEAD"]
    compress                 = true
    cache_policy_id          = aws_cloudfront_cache_policy.origin_controlled.id
    origin_request_policy_id = data.aws_cloudfront_origin_request_policy.all_viewer_except_host.id
  }

  ordered_cache_behavior {
    path_pattern           = "/_next/static/*"
    target_origin_id       = "assets"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    compress               = true
    cache_policy_id        = data.aws_cloudfront_cache_policy.optimized.id
  }

  ordered_cache_behavior {
    path_pattern           = "/images/*"
    target_origin_id       = "assets"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    compress               = true
    cache_policy_id        = data.aws_cloudfront_cache_policy.optimized.id
  }

  # live stock: public and cacheable for one second
  ordered_cache_behavior {
    path_pattern             = "/api/sc/drops/*/stock"
    target_origin_id         = "web"
    viewer_protocol_policy   = "redirect-to-https"
    allowed_methods          = ["GET", "HEAD"]
    cached_methods           = ["GET", "HEAD"]
    compress                 = true
    cache_policy_id          = aws_cloudfront_cache_policy.origin_controlled.id
    origin_request_policy_id = data.aws_cloudfront_origin_request_policy.all_viewer_except_host.id
  }

  # personal: the line, checkout, orders, sign-in
  ordered_cache_behavior {
    path_pattern             = "/api/*"
    target_origin_id         = "web"
    viewer_protocol_policy   = "redirect-to-https"
    allowed_methods          = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods           = ["GET", "HEAD"]
    compress                 = true
    cache_policy_id          = data.aws_cloudfront_cache_policy.disabled.id
    origin_request_policy_id = data.aws_cloudfront_origin_request_policy.all_viewer_except_host.id
  }

  ordered_cache_behavior {
    path_pattern             = "/auth/*"
    target_origin_id         = "web"
    viewer_protocol_policy   = "redirect-to-https"
    allowed_methods          = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods           = ["GET", "HEAD"]
    cache_policy_id          = data.aws_cloudfront_cache_policy.disabled.id
    origin_request_policy_id = data.aws_cloudfront_origin_request_policy.all_viewer_except_host.id
  }

  # payment webhooks: the body must reach the API byte for byte (it is HMAC-signed), so no compression or caching
  ordered_cache_behavior {
    path_pattern             = "/webhooks/*"
    target_origin_id         = "web"
    viewer_protocol_policy   = "https-only"
    allowed_methods          = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods           = ["GET", "HEAD"]
    compress                 = false
    cache_policy_id          = data.aws_cloudfront_cache_policy.disabled.id
    origin_request_policy_id = data.aws_cloudfront_origin_request_policy.all_viewer_except_host.id
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = var.cloudfront_certificate_arn == ""
    acm_certificate_arn            = var.cloudfront_certificate_arn == "" ? null : var.cloudfront_certificate_arn
    ssl_support_method             = var.cloudfront_certificate_arn == "" ? null : "sni-only"
    minimum_protocol_version       = var.cloudfront_certificate_arn == "" ? "TLSv1" : "TLSv1.2_2021"
  }
}
