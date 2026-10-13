locals {
  # prod keeps the plain names the Kubernetes manifests use (role/swiftcart-api, swiftcart-fulfilment.fifo, ...)
  name = var.environment == "prod" ? "swiftcart" : "swiftcart-${var.environment}"
  azs  = slice(data.aws_availability_zones.available.names, 0, 3)
}

# Three AZs. Public subnets hold the ALB and NAT gateways; private /20s hold EKS nodes and pods (VPC CNI gives
# pods VPC IPs, so the subnets are large); database and cache subnets have no route to the internet at all.
module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 5.16"

  name = local.name
  cidr = var.vpc_cidr
  azs  = local.azs

  public_subnets      = [for i, _ in local.azs : cidrsubnet(var.vpc_cidr, 8, i)]
  private_subnets     = [for i, _ in local.azs : cidrsubnet(var.vpc_cidr, 4, i + 1)]
  database_subnets    = [for i, _ in local.azs : cidrsubnet(var.vpc_cidr, 8, i + 100)]
  elasticache_subnets = [for i, _ in local.azs : cidrsubnet(var.vpc_cidr, 8, i + 110)]

  create_database_subnet_group    = true
  create_elasticache_subnet_group = true

  enable_nat_gateway     = true
  single_nat_gateway     = var.single_nat_gateway
  one_nat_gateway_per_az = !var.single_nat_gateway

  enable_dns_hostnames = true
  enable_dns_support   = true

  # the AWS Load Balancer Controller discovers subnets by these tags
  public_subnet_tags  = { "kubernetes.io/role/elb" = 1 }
  private_subnet_tags = { "kubernetes.io/role/internal-elb" = 1 }
}

# S3 traffic from the cluster stays on the AWS network and skips NAT data charges.
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = module.vpc.vpc_id
  service_name      = "com.amazonaws.${var.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = module.vpc.private_route_table_ids

  tags = { Name = "${local.name}-s3" }
}
