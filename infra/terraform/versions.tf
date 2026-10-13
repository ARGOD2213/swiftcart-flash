terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.80"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # Remote state with locking. Fill in with: terraform init -backend-config=backend.hcl
  # (see backend.hcl.example). CI runs `terraform init -backend=false` and never touches state.
  backend "s3" {}
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      project     = "swiftcart"
      environment = var.environment
      managed-by  = "terraform"
    }
  }
}

# CloudFront-scoped resources (the edge WAF) live in us-east-1 whatever the region.
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"

  default_tags {
    tags = {
      project     = "swiftcart"
      environment = var.environment
      managed-by  = "terraform"
    }
  }
}

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

data "aws_availability_zones" "available" {
  state = "available"
}
