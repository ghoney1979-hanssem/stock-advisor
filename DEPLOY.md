# GCP Compute Engine 배포 가이드

단일 VM(e2-medium, 서울 리전)에 앱 + PostgreSQL + Redis를 docker-compose 로 올린다.
**반드시 인스턴스 1개만** 운영한다(스케줄러·인메모리 속도제한기 때문 — 다중 인스턴스 금지).

## 사양
- 인스턴스: `e2-medium` (2 vCPU / 4GB)
- 리전/존: `asia-northeast3`(서울) / `asia-northeast3-a`
- OS: Ubuntu 22.04 LTS
- 디스크: 30GB `pd-balanced`
- 예상 비용: 약 월 $30 (약정 시 ~30% 절감, $300 크레딧으로 수개월 무료)

## 0. 사전 준비 (로컬)
```bash
gcloud auth login
gcloud config set project <YOUR_PROJECT_ID>
gcloud services enable compute.googleapis.com    # Compute Engine API 활성화
```

## 1. VM 생성
```bash
gcloud compute instances create stock-advisor \
  --zone=asia-northeast3-a \
  --machine-type=e2-medium \
  --image-family=ubuntu-2204-lts --image-project=ubuntu-os-cloud \
  --boot-disk-size=30GB --boot-disk-type=pd-balanced \
  --tags=stock-advisor
```
> 인바운드는 SSH(22)만 열면 됨(기본). 앱 8080은 외부 비공개(아래 compose가 127.0.0.1 바인딩).
> DART·KIS·Discord 아웃바운드는 기본 허용.

## 2. 접속 + Docker 설치
```bash
gcloud compute ssh stock-advisor --zone=asia-northeast3-a

# (VM 안에서) Docker + compose 플러그인
sudo apt-get update && sudo apt-get install -y docker.io docker-compose-v2 git
sudo usermod -aG docker $USER && newgrp docker
```

## 3. 스왑 2GB (메모리 안전장치)
```bash
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

## 4. 소스 + 시크릿
```bash
git clone <YOUR_REPO_URL> stock-advisor && cd stock-advisor
# 또는 로컬에서: gcloud compute scp --recurse . stock-advisor:~/stock-advisor --zone=asia-northeast3-a

cp .env.example .env
vi .env     # DB_PASSWORD, DART_API_KEY, KIS_APP_KEY/SECRET, DISCORD_WEBHOOK_URL 채우기
```

## 5. 기동
```bash
docker compose -f docker-compose.prod.yml up -d --build
docker compose -f docker-compose.prod.yml ps
docker logs -f sa-app          # 기동 로그
curl -s localhost:8080/actuator/health   # {"status":"UP"}
```

## 6. 초기 워치리스트 적재 (1회, ~20분)
```bash
curl -s -X POST "localhost:8080/api/v1/admin/sync-watchlist?kospiTop=1000&kosdaqTop=500"
```
이후 평일 16:00 자동 배치로 갱신.

## 운영 메모
- **데이터**: PostgreSQL은 named volume `pgdata` 에 영속. `docker compose ... down` 해도 데이터 유지(`down -v` 는 삭제).
- **업데이트**: `git pull && docker compose -f docker-compose.prod.yml up -d --build`
- **타임존**: 컨테이너 `TZ=Asia/Seoul`, 스케줄 cron 도 Asia/Seoul 고정.
- **관리 API**(`/api/v1/admin/*`, actuator)는 외부 비공개. 필요 시 SSH 터널:
  `gcloud compute ssh stock-advisor --zone=asia-northeast3-a -- -L 8080:localhost:8080`
- **KIS 실전 계좌 키** 사용 중(시세 조회만, 매매 없음). 모의면 `.env` 의 `KIS_BASE_URL` 변경.
- **단일 인스턴스 유지** — 절대 복제/오토스케일 금지.
- 중지: `docker compose -f docker-compose.prod.yml stop` / VM 중지로 과금 절감 가능(상태는 volume 보존).
