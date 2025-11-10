terraform {
  # 테라폼으로 AWS를 다루기 위해 AWS 라이브러리 불러옴
  required_providers {
    aws = {
      source = "hashicorp/aws"
    }
  }
}

# AWS 설정
provider "aws" {
  region = var.region
}

# VPC 네트워크 생성 (사용자 전용 가상 네트워크)
resource "aws_vpc" "vpc_1" {
  cidr_block = "10.0.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true
}

# 서브넷 생성 (vpc_1 VPC에 속함)
resource "aws_subnet" "subnet_1" {
  vpc_id                  = aws_vpc.vpc_1.id
  cidr_block              = "10.0.0.0/24"
  availability_zone       = "${var.region}a" # 가용 영역 a
  map_public_ip_on_launch = true
}
resource "aws_subnet" "subnet_2" {
  vpc_id                  = aws_vpc.vpc_1.id
  cidr_block              = "10.0.1.0/24"
  availability_zone       = "${var.region}b" # 가용 영역 b
  map_public_ip_on_launch = true
}
resource "aws_subnet" "subnet_3" {
  vpc_id                  = aws_vpc.vpc_1.id
  cidr_block              = "10.0.2.0/24"
  availability_zone       = "${var.region}c" # 가용 영역 c
  map_public_ip_on_launch = true
}
resource "aws_subnet" "subnet_4" {
  vpc_id                  = aws_vpc.vpc_1.id
  cidr_block              = "10.0.3.0/24"
  availability_zone       = "${var.region}d" # 가용 영역 d
  map_public_ip_on_launch = true
}

# 인터넷 게이트웨이 생성 (VPC가 외부 인터넷과 통신할 수 있도록)
resource "aws_internet_gateway" "igw_1" {
  vpc_id = aws_vpc.vpc_1.id
}

# 라우팅 테이블 생성 (모든 네트워크 트래픽이 인터넷 게이트웨이를 통하도록 규칙 정의)
resource "aws_route_table" "rt_1" {
  vpc_id = aws_vpc.vpc_1.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.igw_1.id
  }
}

# 라우팅 테이블의 규칙을 4개의 서브넷에 모두 적용
resource "aws_route_table_association" "association_1" {
  subnet_id      = aws_subnet.subnet_1.id
  route_table_id = aws_route_table.rt_1.id
}
resource "aws_route_table_association" "association_2" {
  subnet_id      = aws_subnet.subnet_2.id
  route_table_id = aws_route_table.rt_1.id
}
resource "aws_route_table_association" "association_3" {
  subnet_id      = aws_subnet.subnet_3.id
  route_table_id = aws_route_table.rt_1.id
}
resource "aws_route_table_association" "association_4" {
  subnet_id      = aws_subnet.subnet_4.id
  route_table_id = aws_route_table.rt_1.id
}

# 방화벽(=보안 그룹)
resource "aws_security_group" "sg_1" {
  name = "${var.prefix}-sg-1"
  ingress { # 외부에서 EC2 인스턴스로 들어오는 모든 트래픽 허용
    from_port = 0
    to_port   = 0
    protocol  = "all"
    cidr_blocks = ["0.0.0.0/0"]
  }
  egress { # EC2 인스턴스에서 외부로 나가는 모든 트래픽 허용
    from_port = 0
    to_port   = 0
    protocol  = "all"
    cidr_blocks = ["0.0.0.0/0"]
  }
  vpc_id = aws_vpc.vpc_1.id
}

# EC2 인스턴스 설정
# 역할 생성
resource "aws_iam_role" "ec2_role_1" {
  name = "${var.prefix}-ec2-role-1"
  assume_role_policy = <<EOF
  {
    "Version": "2012-10-17",
    "Statement": [
      {
        "Sid": "",
        "Action": "sts:AssumeRole",
        "Principal": {
            "Service": "ec2.amazonaws.com"
        },
        "Effect": "Allow"
      }
    ]
  }
  EOF
}
# EC2 역할에 AmazonS3FullAccess 정책 연결
resource "aws_iam_role_policy_attachment" "s3_full_access" {
  role       = aws_iam_role.ec2_role_1.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonS3FullAccess"
}
# EC2 역할에 AmazonEC2RoleforSSM 정책 연결
resource "aws_iam_role_policy_attachment" "ec2_ssm" {
  role       = aws_iam_role.ec2_role_1.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonEC2RoleforSSM"
}
# EC2와 정책을 연결해주는 IAM 인스턴스 프로파일 생성
resource "aws_iam_instance_profile" "instance_profile_1" {
  name = "${var.prefix}-instance-profile-1"
  role = aws_iam_role.ec2_role_1.name
}

# EC2 인스턴스가 처음 부팅될 때 실행할 셸 스크립트
locals {
  ec2_user_data_base = <<-END_OF_FILE
#!/bin/bash

# 가상 메모리 설정 (4GB)
dd if=/dev/zero of=/swapfile bs=128M count=32
chmod 600 /swapfile
mkswap /swapfile
swapon /swapfile
sh -c 'echo "/swapfile swap swap defaults 0 0" >> /etc/fstab'

# 타임존 설정 (서버 시간대를 서울로)
timedatectl set-timezone Asia/Seoul

# 환경변수 설정 (/etc/environment 파일에 추가)
echo "PASSWORD=${var.password}" >> /etc/environment
echo "APP_DOMAIN=${var.morimori_domain}" >> /etc/environment
echo "GITHUB_ACCESS_TOKEN_OWNER=${var.github_access_token_owner}" >> /etc/environment
echo "GITHUB_ACCESS_TOKEN=${var.github_access_token}" >> /etc/environment
source /etc/environment

# 도커 설치 및 실행
yum install docker -y
systemctl enable docker
systemctl start docker

# Docker Compose 설치
curl -SL https://github.com/docker/compose/releases/download/v2.39.4/docker-compose-linux-x86_64 -o /usr/local/bin/docker-compose
chmod +x /usr/local/bin/docker-compose
ln -s /usr/local/bin/docker-compose /usr/bin/docker-compose

# Git 설치 및 Github에서 소스 코드 클론
yum install git -y
git clone https://${var.github_access_token_owner}:${var.github_access_token}@github.com/${var.github_repo_owner}/${var.github_repo_name}.git /app/mori-mori
cd /app/mori-mori

# Docker Compose를 위한 .env 파일 생성
cat <<EOF > .env
NPM_ADMIN_EMAIL=admin@npm.com
NPM_ADMIN_PASSWORD=${var.password}
REDIS_PASSWORD=${var.password}
DB_HOST=${aws_db_instance.rds_postgres.endpoint}
DB_PORT=5432
DB_NAME=morimori
DB_USERNAME=${var.db_username}
DB_PASSWORD=${var.db_password}
APP_DOMAIN=${var.morimori_domain}
GITHUB_ACCESS_TOKEN_OWNER=${var.github_access_token_owner}
GITHUB_ACCESS_TOKEN=${var.github_access_token}
EOF

# GHCR 로그인
echo "${var.github_access_token}" | docker login ghcr.io -u ${var.github_access_token_owner} --password-stdin

# Docker Compose 실행
docker-compose up -d

END_OF_FILE
}

# 최신 Amazon Linux 2023 AMI 조회 (프리티어 호환)
data "aws_ami" "latest_amazon_linux" {
  most_recent = true
  owners = ["amazon"]
  filter {
    name = "name"
    values = ["al2023-ami-2023.*-x86_64"]
  }
  filter {
    name = "architecture"
    values = ["x86_64"]
  }
  filter {
    name = "virtualization-type"
    values = ["hvm"]
  }
  filter {
    name = "root-device-type"
    values = ["ebs"]
  }
}

# EC2 인스턴스 생성
resource "aws_instance" "ec2_1" {
  ami = data.aws_ami.latest_amazon_linux.id  # 위에서 조회한 AMI ID 사용
  instance_type = "t3.micro"  # EC2 인스턴스 유형
  subnet_id = aws_subnet.subnet_2.id  # 사용할 서브넷 ID
  vpc_security_group_ids = [aws_security_group.sg_1.id]  # 적용할 보안 그룹 ID
  associate_public_ip_address = false  # 퍼블릭 IP 연결 설정 (탄력적 IP를 사용하므로 false로 변경)
  iam_instance_profile = aws_iam_instance_profile.instance_profile_1.name  # 인스턴스에 IAM 역할 연결하여 권한 부여

  # 인스턴스에 태그 설정
  tags = {
    Name = "${var.prefix}-ec2"
  }

  # 루트 볼륨(하드 디스크) 설정
  root_block_device {
    volume_type = "gp3"
    volume_size = 30 # 볼륨 크기를 12GB로 설정
  }

  user_data = <<-EOF
${local.ec2_user_data_base}
EOF
}

# 기존에 생성된 탄력적 IP 조회
data "aws_eip" "existing_eip" {
  public_ip = "15.164.37.181"
}
# 탄력적 IP를 EC2 인스턴스에 연결
resource "aws_eip_association" "eip_assoc" {
  instance_id   = aws_instance.ec2_1.id
  allocation_id = data.aws_eip.existing_eip.id
}

# RDS 설정
# RDS 보안 그룹 생성
resource "aws_security_group" "rds_sg" {
  name        = "${var.prefix}-rds-sg"
  vpc_id      = aws_vpc.vpc_1.id
  ingress { # 외부에서 RDS로 들어오는 트래픽 규칙
    from_port       = 5432 # PostgreSQL 기본 포트인 5432 포트만 허용
    to_port         = 5432
    protocol        = "tcp" # TCP 프로토콜만 허용
    security_groups = [aws_security_group.sg_1.id]  # EC2 인스턴스로부터의 접속만 허용
  }
  egress { # RDS에서 외부로 나가는 모든 트래픽 허용
    description = "Allow all outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# RDS 서브넷 그룹
resource "aws_db_subnet_group" "rds_subnets" {
  name       = "${var.prefix}-rds-subnet-group"
  subnet_ids = [aws_subnet.subnet_3.id, aws_subnet.subnet_4.id]  # 서브넷 3과 4 사용
}

# PostgreSQL 인스턴스 생성
resource "aws_db_instance" "rds_postgres" {
  identifier        = "${var.prefix}-postgresql" # RDS 인스턴스 이름
  engine            = "postgres" # 엔진
  engine_version    = "16.10" # DB 버전
  instance_class    = "db.t3.micro" # DB 사양
  storage_type      = "gp2" # 범용 SSD 스토리지
  allocated_storage = 20 # 스토리지 20GB
  # max_allocated_storage 설정 x → 스토리지 자동 조정 비활성화

  db_name = "morimori" # DB 이름
  username = var.db_username # 계정명
  password = var.db_password # 계정 비번

  multi_az = false # 다중 AZ 비활성화
  backup_retention_period = 0 # 자동 백업 비활성화
  skip_final_snapshot = true # DB 인스턴스 삭제 시 스냅샷 생성 안함

  db_subnet_group_name   = aws_db_subnet_group.rds_subnets.name # 서브넷 그룹 적용
  vpc_security_group_ids = [aws_security_group.rds_sg.id] # 보안 그룹 적용(EC2만 접근 가능)
  publicly_accessible = false # 퍼블릭 IP 없음. 외부에서 DB 접근 불가능
  auto_minor_version_upgrade = false # 마이너 버전 자동 업그레이드 비활성화
}
