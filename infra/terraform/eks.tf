# EKS with managed nodes across three AZs. Add-ons installed afterwards with Helm (see README):
# AWS Load Balancer Controller, KEDA, External Secrets Operator, Argo CD, OpenTelemetry Collector.
module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 20.31"

  cluster_name    = local.name
  cluster_version = var.cluster_version

  cluster_endpoint_public_access = true
  enable_irsa                    = true
  cluster_enabled_log_types      = ["api", "audit", "authenticator"]

  authentication_mode                      = "API"
  enable_cluster_creator_admin_permissions = true
  access_entries = {
    for i, arn in var.cluster_admin_arns : "admin-${i}" => {
      principal_arn = arn
      policy_associations = {
        admin = {
          policy_arn   = "arn:${data.aws_partition.current.partition}:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy"
          access_scope = { type = "cluster" }
        }
      }
    }
  }

  cluster_addons = {
    coredns    = {}
    kube-proxy = {}
    vpc-cni    = { before_compute = true }
  }

  vpc_id                   = module.vpc.vpc_id
  subnet_ids               = module.vpc.private_subnets
  control_plane_subnet_ids = module.vpc.private_subnets

  eks_managed_node_groups = {
    general = {
      ami_type       = "AL2023_x86_64_STANDARD"
      instance_types = var.node_instance_types
      capacity_type  = var.node_capacity_type

      min_size     = var.node_min_size
      max_size     = var.node_max_size
      desired_size = var.node_min_size

      labels = { workload = "general" }
    }
  }
}
