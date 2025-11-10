variable "region" {
  description = "region"
  default     = "ap-northeast-2"
}

variable "prefix" {
  description = "Prefix for all resources"
  default     = "morimori"
}

variable "morimori_domain" {
  description = "morimori domain"
  default     = "api.mori-mori.store"
}

variable "github_repo_owner" {
  description = "깃허브 조직"
  type        = string
  default     = "morimori-store"
}

variable "github_repo_name" {
  description = "레포지터리"
  type        = string
  default     = "Backend"
}