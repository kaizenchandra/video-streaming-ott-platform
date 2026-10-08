terraform {

  required_version = ">= 1.10, < 2.0"
  required_providers {
    aws = { source = "hashicorp/aws", version = "6.38.0" }
  }

  backend "s3" {

  }


}

provider "aws" {

  region = var.region
  default_tags {
    tags = { Project = var.name, ManagedBy = "Terraform" }
  }


}

provider "aws" {
  alias  = "global"
  region = "us-east-1"
}
data "aws_caller_identity" "current" {}
variable "name" {

  type    = string
  default = "ott"
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{2,19}$", var.name))
    error_message = "Use a short lowercase deployment name."
  }


}

variable "region" {
  type = string
}

variable "availability_zones" {

  type = list(string)
  validation {
    condition     = length(distinct(var.availability_zones)) == 3
    error_message = "Exactly three distinct AZs are required."
  }


}

variable "operator_role_arn" {
  type = string
}

variable "cdn_public_key_pem" {
  type = string
}

variable "search_master_role_arn" {
  type = string
}

variable "redis_auth_token" {
  type      = string
  sensitive = true
}

variable "scram_secret_arns" {
  type = list(string)
}

locals {

  owners = toset(["member", "content", "commerce", "playback", "media", "experience", "engagement", "insights", "operations"])
  azs    = { for index, az in var.availability_zones : az => index }

}

resource "aws_kms_key" "data" {
  description             = "OTT data encryption"
  enable_key_rotation     = true
  deletion_window_in_days = 30
}

resource "aws_vpc" "main" {
  cidr_block           = "10.42.0.0/16"
  enable_dns_hostnames = true
  enable_dns_support   = true
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id
}

resource "aws_subnet" "public" {

  for_each          = local.azs
  vpc_id            = aws_vpc.main.id
  availability_zone = each.key
  cidr_block        = cidrsubnet(aws_vpc.main.cidr_block, 8, each.value)
  tags              = { "kubernetes.io/role/elb" = "1" }

}

resource "aws_subnet" "private" {

  for_each          = local.azs
  vpc_id            = aws_vpc.main.id
  availability_zone = each.key
  cidr_block        = cidrsubnet(aws_vpc.main.cidr_block, 8, each.value + 10)
  tags              = { "kubernetes.io/role/internal-elb" = "1" }

}

resource "aws_eip" "nat" {
  for_each = local.azs
  domain   = "vpc"
}

resource "aws_nat_gateway" "main" {

  for_each      = local.azs
  allocation_id = aws_eip.nat[each.key].id
  subnet_id     = aws_subnet.public[each.key].id
  depends_on    = [aws_internet_gateway.main]

}

resource "aws_route_table" "public" {

  vpc_id = aws_vpc.main.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }


}

resource "aws_route_table_association" "public" {
  for_each       = local.azs
  subnet_id      = aws_subnet.public[each.key].id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table" "private" {

  for_each = local.azs
  vpc_id   = aws_vpc.main.id
  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.main[each.key].id
  }


}

resource "aws_route_table_association" "private" {
  for_each       = local.azs
  subnet_id      = aws_subnet.private[each.key].id
  route_table_id = aws_route_table.private[each.key].id
}

resource "aws_vpc_endpoint" "s3" {

  vpc_id            = aws_vpc.main.id
  service_name      = "com.amazonaws.${var.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [for r in aws_route_table.private : r.id]

}

resource "aws_iam_role" "cluster" {

  name               = "${var.name}-cluster"
  assume_role_policy = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Principal = { Service = "eks.amazonaws.com" }, Action = "sts:AssumeRole" }] })

}

resource "aws_iam_role_policy_attachment" "cluster" {
  role       = aws_iam_role.cluster.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSClusterPolicy"
}

resource "aws_eks_cluster" "main" {

  name                      = var.name
  version                   = "1.35"
  role_arn                  = aws_iam_role.cluster.arn
  enabled_cluster_log_types = ["api", "audit", "authenticator", "controllerManager", "scheduler"]
  access_config {
    authentication_mode                         = "API"
    bootstrap_cluster_creator_admin_permissions = false
  }

  encryption_config {
    provider {
      key_arn = aws_kms_key.data.arn
    }

    resources = ["secrets"]

  }

  vpc_config {
    subnet_ids              = [for s in aws_subnet.private : s.id]
    endpoint_private_access = true
    endpoint_public_access  = false
  }

  depends_on = [aws_iam_role_policy_attachment.cluster]

}

resource "aws_eks_access_entry" "operator" {
  cluster_name  = aws_eks_cluster.main.name
  principal_arn = var.operator_role_arn
}

resource "aws_eks_access_policy_association" "operator" {

  cluster_name  = aws_eks_cluster.main.name
  principal_arn = aws_eks_access_entry.operator.principal_arn
  policy_arn    = "arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy"
  access_scope {
    type = "cluster"
  }


}

resource "aws_iam_role" "nodes" {

  name               = "${var.name}-nodes"
  assume_role_policy = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Principal = { Service = "ec2.amazonaws.com" }, Action = "sts:AssumeRole" }] })

}

resource "aws_iam_role_policy_attachment" "nodes" {

  for_each   = toset(["AmazonEKSWorkerNodePolicy", "AmazonEC2ContainerRegistryReadOnly", "AmazonEKS_CNI_Policy"])
  role       = aws_iam_role.nodes.name
  policy_arn = "arn:aws:iam::aws:policy/${each.key}"

}

resource "aws_eks_node_group" "main" {

  cluster_name    = aws_eks_cluster.main.name
  node_group_name = "owners"
  node_role_arn   = aws_iam_role.nodes.arn
  subnet_ids      = [for s in aws_subnet.private : s.id]
  instance_types  = ["m7i.xlarge"]
  capacity_type   = "ON_DEMAND"
  launch_template {
    id      = aws_launch_template.nodes.id
    version = "$Latest"
  }
  scaling_config {
    desired_size = 3
    min_size     = 3
    max_size     = 12
  }

  update_config {
    max_unavailable = 1
  }

  depends_on = [aws_iam_role_policy_attachment.nodes]

}

resource "aws_iam_openid_connect_provider" "eks" {

  url            = aws_eks_cluster.main.identity[0].oidc[0].issuer
  client_id_list = ["sts.amazonaws.com"]

}

resource "aws_secretsmanager_secret" "owner" {
  for_each                = local.owners
  name                    = "${var.name}/${each.key}/application"
  kms_key_id              = aws_kms_key.data.arn
  recovery_window_in_days = 30
}

resource "aws_iam_role" "owner" {

  for_each           = local.owners
  name               = "${var.name}-${each.key}"
  assume_role_policy = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Action = "sts:AssumeRoleWithWebIdentity", Principal = { Federated = aws_iam_openid_connect_provider.eks.arn }, Condition = { StringEquals = { "${replace(aws_iam_openid_connect_provider.eks.url, "https://", "")}:aud" = "sts.amazonaws.com", "${replace(aws_iam_openid_connect_provider.eks.url, "https://", "")}:sub" = "system:serviceaccount:ott:${var.name}-${each.key}" } } }] })

}

resource "aws_iam_role_policy" "owner" {

  for_each = local.owners
  role     = aws_iam_role.owner[each.key].id
  policy   = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Action = ["secretsmanager:GetSecretValue"], Resource = aws_secretsmanager_secret.owner[each.key].arn }, { Effect = "Allow", Action = ["kms:Decrypt"], Resource = aws_kms_key.data.arn, Condition = { StringEquals = { "kms:ViaService" = "secretsmanager.${var.region}.amazonaws.com" } } }] })

}

resource "aws_security_group" "database" {

  name   = "${var.name}-database"
  vpc_id = aws_vpc.main.id
  ingress {
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_eks_cluster.main.vpc_config[0].cluster_security_group_id]
  }


}

resource "aws_db_subnet_group" "main" {
  name       = var.name
  subnet_ids = [for s in aws_subnet.private : s.id]
}

resource "aws_db_instance" "owner" {

  for_each                        = local.owners
  identifier                      = "${var.name}-${each.key}"
  engine                          = "postgres"
  engine_version                  = "17"
  instance_class                  = "db.t4g.medium"
  allocated_storage               = 30
  max_allocated_storage           = 300
  storage_type                    = "gp3"
  storage_encrypted               = true
  kms_key_id                      = aws_kms_key.data.arn
  db_name                         = each.key
  username                        = "migration_admin"
  manage_master_user_password     = true
  master_user_secret_kms_key_id   = aws_kms_key.data.arn
  db_subnet_group_name            = aws_db_subnet_group.main.name
  vpc_security_group_ids          = [aws_security_group.database.id]
  multi_az                        = true
  publicly_accessible             = false
  backup_retention_period         = 35
  deletion_protection             = true
  skip_final_snapshot             = false
  final_snapshot_identifier       = "${var.name}-${each.key}-final"
  auto_minor_version_upgrade      = true
  enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]

}

resource "aws_security_group" "streaming" {

  name   = "${var.name}-streaming"
  vpc_id = aws_vpc.main.id
  ingress {
    from_port       = 9096
    to_port         = 9096
    protocol        = "tcp"
    security_groups = [aws_eks_cluster.main.vpc_config[0].cluster_security_group_id]
  }

  ingress {
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [aws_eks_cluster.main.vpc_config[0].cluster_security_group_id]
  }

  ingress {
    from_port       = 443
    to_port         = 443
    protocol        = "tcp"
    security_groups = [aws_eks_cluster.main.vpc_config[0].cluster_security_group_id]
  }


}

resource "aws_msk_configuration" "main" {

  name              = "${var.name}-kafka"
  kafka_versions    = ["3.9.x"]
  server_properties = "auto.create.topics.enable=false\ndefault.replication.factor=3\nmin.insync.replicas=2\nunclean.leader.election.enable=false\nallow.everyone.if.no.acl.found=false"

}

resource "aws_msk_cluster" "main" {

  cluster_name           = var.name
  kafka_version          = "3.9.x"
  number_of_broker_nodes = 3
  broker_node_group_info {
    instance_type   = "kafka.m7g.large"
    client_subnets  = [for s in aws_subnet.private : s.id]
    security_groups = [aws_security_group.streaming.id]
    storage_info {
      ebs_storage_info {
        volume_size = 100
      }

    }


  }

  encryption_info {
    encryption_at_rest_kms_key_arn = aws_kms_key.data.arn
    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }


  }

  client_authentication {
    sasl {
      scram = true
    }
    unauthenticated = false
  }

  configuration_info {
    arn      = aws_msk_configuration.main.arn
    revision = aws_msk_configuration.main.latest_revision
  }


}

resource "aws_msk_scram_secret_association" "main" {
  cluster_arn     = aws_msk_cluster.main.arn
  secret_arn_list = var.scram_secret_arns
}

resource "aws_elasticache_subnet_group" "main" {
  name       = var.name
  subnet_ids = [for s in aws_subnet.private : s.id]
}

resource "aws_elasticache_replication_group" "main" {

  replication_group_id       = var.name
  description                = "Playback disposable leases; PostgreSQL is authoritative"
  engine                     = "redis"
  engine_version             = "7.1"
  node_type                  = "cache.t4g.small"
  parameter_group_name       = "default.redis7.cluster.on"
  num_node_groups            = 3
  replicas_per_node_group    = 1
  automatic_failover_enabled = true
  multi_az_enabled           = true
  subnet_group_name          = aws_elasticache_subnet_group.main.name
  security_group_ids         = [aws_security_group.streaming.id]
  at_rest_encryption_enabled = true
  kms_key_id                 = aws_kms_key.data.arn
  transit_encryption_enabled = true
  auth_token                 = var.redis_auth_token
  auth_token_update_strategy = "SET"
  snapshot_retention_limit   = 1

}

resource "aws_opensearch_domain" "main" {

  domain_name    = var.name
  engine_version = "OpenSearch_2.19"
  cluster_config {
    instance_type          = "m7g.large.search"
    instance_count         = 3
    zone_awareness_enabled = true
    zone_awareness_config {
      availability_zone_count = 3
    }

    dedicated_master_enabled = true
    dedicated_master_type    = "m7g.large.search"
    dedicated_master_count   = 3

  }

  ebs_options {
    ebs_enabled = true
    volume_type = "gp3"
    volume_size = 30
  }

  vpc_options {
    subnet_ids         = [for s in aws_subnet.private : s.id]
    security_group_ids = [aws_security_group.streaming.id]
  }

  encrypt_at_rest {
    enabled    = true
    kms_key_id = aws_kms_key.data.arn
  }

  node_to_node_encryption {
    enabled = true
  }

  domain_endpoint_options {
    enforce_https       = true
    tls_security_policy = "Policy-Min-TLS-1-2-2019-07"
  }

  advanced_security_options {
    enabled                        = true
    internal_user_database_enabled = true
    master_user_options {
      master_user_arn = var.search_master_role_arn
    }


  }

  access_policies = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Principal = "*", Action = "es:ESHttp*", Resource = "arn:aws:es:${var.region}:*:domain/${var.name}/*" }] })

}

resource "aws_s3_bucket" "media" {
  for_each      = toset(["quarantine", "origin"])
  bucket_prefix = "${var.name}-${each.key}-"
  force_destroy = false
}

resource "aws_s3_bucket_public_access_block" "media" {

  for_each                = aws_s3_bucket.media
  bucket                  = each.value.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true

}

resource "aws_s3_bucket_versioning" "media" {
  for_each = aws_s3_bucket.media
  bucket   = each.value.id
  versioning_configuration {
    status = "Enabled"
  }

}

resource "aws_s3_bucket_server_side_encryption_configuration" "media" {

  for_each = aws_s3_bucket.media
  bucket   = each.value.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.media.arn
    }

  }


}

resource "aws_cloudfront_public_key" "playback" {
  name        = var.name
  encoded_key = var.cdn_public_key_pem
}

resource "aws_cloudfront_key_group" "playback" {
  name  = var.name
  items = [aws_cloudfront_public_key.playback.id]
}

resource "aws_cloudfront_origin_access_control" "media" {
  name                              = var.name
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

resource "aws_cloudfront_distribution" "media" {

  enabled     = true
  price_class = "PriceClass_100"
  web_acl_id  = aws_wafv2_web_acl.media.arn
  origin {
    domain_name              = aws_s3_bucket.media["origin"].bucket_regional_domain_name
    origin_id                = "private-origin"
    origin_access_control_id = aws_cloudfront_origin_access_control.media.id
  }

  default_cache_behavior {

    target_origin_id       = "private-origin"
    viewer_protocol_policy = "https-only"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    trusted_key_groups     = [aws_cloudfront_key_group.playback.id]
    cache_policy_id        = "658327ea-f89d-4fab-a63d-7e88639e58f6"

  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }

  }

  viewer_certificate {
    cloudfront_default_certificate = true
  }


}

resource "aws_s3_bucket_policy" "media" {

  for_each = aws_s3_bucket.media
  bucket   = each.value.id
  policy   = jsonencode({ Version = "2012-10-17", Statement = concat([{ Sid = "DenyInsecureTransport", Effect = "Deny", Principal = "*", Action = "s3:*", Resource = [each.value.arn, "${each.value.arn}/*"], Condition = { Bool = { "aws:SecureTransport" = "false" } } }], each.key == "origin" ? [{ Sid = "CloudFrontRead", Effect = "Allow", Principal = { Service = "cloudfront.amazonaws.com" }, Action = "s3:GetObject", Resource = "${each.value.arn}/*", Condition = { StringEquals = { "AWS:SourceArn" = aws_cloudfront_distribution.media.arn } } }] : []) })

}

resource "aws_ecr_repository" "owner" {
  for_each             = local.owners
  name                 = "${var.name}/${each.key}"
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "KMS"
    kms_key         = aws_kms_key.data.arn
  }

}

output "private_subnets" {
  value = [for s in aws_subnet.private : s.id]
}

output "owner_roles" {
  value = { for owner, role in aws_iam_role.owner : owner => role.arn }
}

output "owner_database_endpoints" {
  value = { for owner, db in aws_db_instance.owner : owner => db.endpoint }
}

output "owner_application_secrets" {
  value = { for owner, secret in aws_secretsmanager_secret.owner : owner => secret.arn }
}

output "kafka_scram_bootstrap" {
  value = aws_msk_cluster.main.bootstrap_brokers_sasl_scram
}

output "redis_configuration_endpoint" {
  value = aws_elasticache_replication_group.main.configuration_endpoint_address
}

output "search_endpoint" {
  value = aws_opensearch_domain.main.endpoint
}

output "origin_bucket" {
  value = aws_s3_bucket.media["origin"].id
}

output "cdn_domain" {
  value = aws_cloudfront_distribution.media.domain_name
}


resource "aws_security_group_rule" "broker_internal" {
  type              = "ingress"
  from_port         = 0
  to_port           = 0
  protocol          = "-1"
  self              = true
  security_group_id = aws_security_group.streaming.id
}
resource "aws_launch_template" "nodes" {
  name_prefix = "${var.name}-"
  metadata_options {
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }
  block_device_mappings {
    device_name = "/dev/xvda"
    ebs {
      encrypted   = true
      volume_type = "gp3"
      volume_size = 40
    }
  }
}
resource "aws_iam_role_policy" "media_storage" {
  role   = aws_iam_role.owner["media"].id
  policy = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Action = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"], Resource = ["${aws_s3_bucket.media["quarantine"].arn}/*", "${aws_s3_bucket.media["origin"].arn}/assets/*"] }, { Effect = "Allow", Action = ["s3:ListBucket"], Resource = [aws_s3_bucket.media["quarantine"].arn, aws_s3_bucket.media["origin"].arn] }] })
}
resource "aws_wafv2_web_acl" "api" {
  name  = "${var.name}-api"
  scope = "REGIONAL"
  default_action {
    allow {}
  }
  rule {
    name     = "managed-common"
    priority = 0
    override_action {
      none {}
    }
    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesCommonRuleSet"
        vendor_name = "AWS"
      }
    }
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name}-common"
      sampled_requests_enabled   = false
    }
  }
  rule {
    name     = "ip-rate"
    priority = 1
    action {
      block {}
    }
    statement {
      rate_based_statement {
        limit              = 2000
        aggregate_key_type = "IP"
      }
    }
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name}-rate"
      sampled_requests_enabled   = false
    }
  }
  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "${var.name}-waf"
    sampled_requests_enabled   = false
  }
}
output "api_waf_arn" { value = aws_wafv2_web_acl.api.arn }

resource "aws_kms_key" "media" {
  description             = "Media object encryption and scoped CloudFront decryption"
  enable_key_rotation     = true
  deletion_window_in_days = 30
}
resource "aws_kms_key_policy" "media" {
  key_id = aws_kms_key.media.id
  policy = jsonencode({ Version = "2012-10-17", Statement = [{ Sid = "AccountAdministration", Effect = "Allow", Principal = { AWS = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root" }, Action = "kms:*", Resource = "*" }, { Sid = "CloudFrontOriginDecrypt", Effect = "Allow", Principal = { Service = "cloudfront.amazonaws.com" }, Action = "kms:Decrypt", Resource = "*", Condition = { StringEquals = { "AWS:SourceArn" = aws_cloudfront_distribution.media.arn } } }] })
}
resource "aws_iam_role_policy" "media_crypto" {
  role   = aws_iam_role.owner["media"].id
  policy = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Action = ["kms:Decrypt", "kms:GenerateDataKey"], Resource = aws_kms_key.media.arn, Condition = { StringEquals = { "kms:ViaService" = "s3.${var.region}.amazonaws.com" } } }] })
}
resource "aws_wafv2_web_acl" "media" {
  provider = aws.global
  name     = "${var.name}-media"
  scope    = "CLOUDFRONT"
  default_action {
    allow {}
  }
  rule {
    name     = "ip-rate"
    priority = 0
    action {
      block {}
    }
    statement {
      rate_based_statement {
        limit              = 10000
        aggregate_key_type = "IP"
      }
    }
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name}-media-rate"
      sampled_requests_enabled   = false
    }
  }
  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "${var.name}-media-waf"
    sampled_requests_enabled   = false
  }
}
