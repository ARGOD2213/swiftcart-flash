# cp example.tfvars prod.tfvars && terraform plan -var-file=prod.tfvars
region      = "ap-south-1"
environment = "prod"

cluster_admin_arns = [
  # "arn:aws:iam::123456789012:role/platform-admins",
]

# a modest baseline; KEDA pre-scales checkout pods (and the node group grows) before each drop
node_min_size  = 2
node_max_size  = 20
aurora_max_acu = 32

shop_domain       = "shop.example.com"
web_origin_domain = "origin.shop.example.com" # the ALB, via a DNS alias
# cloudfront_certificate_arn = "arn:aws:acm:us-east-1:123456789012:certificate/..."
ses_from_address = "orders@shop.example.com"

# alarm_topic_arn = "arn:aws:sns:ap-south-1:123456789012:oncall"
