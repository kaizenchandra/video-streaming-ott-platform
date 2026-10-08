mock_provider "aws" {
  mock_data "aws_caller_identity" { defaults = { account_id = "123456789012" } }
}
mock_provider "aws" {
  alias = "global"
  mock_resource "aws_wafv2_web_acl" {
    override_during = plan
    defaults        = { arn = "arn:aws:wafv2:us-east-1:123456789012:global/webacl/fixture/11111111-1111-1111-1111-111111111111" }
  }
}
variables {
  region                 = "us-east-1"
  availability_zones     = ["us-east-1a", "us-east-1b", "us-east-1c"]
  operator_role_arn      = "arn:aws:iam::123456789012:role/operator"
  search_master_role_arn = "arn:aws:iam::123456789012:role/search-admin"
  cdn_public_key_pem     = "PUBLIC_KEY_TEST_FIXTURE_NO_CLOUD_APPLY"
  redis_auth_token       = "OnlyProviderMockFixtureNotARealCredential"
  scram_secret_arns      = ["arn:aws:secretsmanager:us-east-1:123456789012:secret:AmazonMSK_fixture"]
}
run "security_baseline" {
  command = plan
  assert {
    condition     = alltrue([for db in aws_db_instance.owner : db.multi_az && db.storage_encrypted && !db.publicly_accessible && db.deletion_protection && db.backup_retention_period >= 35])
    error_message = "Owner databases require private encrypted durable Multi-AZ storage."
  }
  assert {
    condition     = length(aws_db_instance.owner) == 9 && length(aws_iam_role.owner) == 9
    error_message = "Owner isolation is mandatory."
  }
  assert {
    condition     = aws_eks_cluster.main.vpc_config[0].endpoint_private_access && !aws_eks_cluster.main.vpc_config[0].endpoint_public_access
    error_message = "EKS API must be private."
  }
  assert {
    condition     = aws_msk_cluster.main.number_of_broker_nodes == 3 && aws_msk_cluster.main.client_authentication[0].sasl[0].scram
    error_message = "Kafka requires three brokers and authenticated TLS."
  }
  assert {
    condition     = aws_elasticache_replication_group.main.transit_encryption_enabled && aws_elasticache_replication_group.main.automatic_failover_enabled
    error_message = "Redis requires TLS and failover."
  }
  assert {
    condition     = alltrue([for p in aws_s3_bucket_public_access_block.media : p.block_public_acls && p.block_public_policy]) && aws_cloudfront_distribution.media.default_cache_behavior[0].viewer_protocol_policy == "https-only" && aws_cloudfront_distribution.media.web_acl_id != ""
    error_message = "Media origin must be private and delivery HTTPS only."
  }
}
run "single_az_rejected" {
  command = plan
  variables { availability_zones = ["us-east-1a"] }
  expect_failures = [var.availability_zones]
}
