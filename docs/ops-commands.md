# 운영 명령어 모음 (GCP 배포 운영)

배포된 stock-advisor 서비스를 로컬에서 운영/점검할 때 참고. 실제 배포 절차는 `DEPLOY.md` 참고.

## 환경 정보
| 항목 | 값 |
|------|-----|
| GCP 프로젝트 | `project-9fcd8a2b-223d-49e2-b37` |
| VM 이름 | `stock-advisor` |
| 존(Zone) | `asia-northeast3-a` (서울) |
| 머신 타입 | `e2-medium` (2 vCPU / 4GB) |
| 외부 IP | `34.64.120.140` |
| 앱 포트 | 8080 (외부 비공개, localhost 바인딩) |

> `gcloud` 가 PATH에 없으면 먼저:
> `export PATH=/opt/homebrew/share/google-cloud-sdk/bin:"$PATH"`
> (또는 새 터미널에서 ~/.zshrc 가 자동 적용)

## 사전 (인증)
```bash
gcloud auth login                                  # 최초 1회 (브라우저)
gcloud config set project project-9fcd8a2b-223d-49e2-b37
```

## 접속 / 로그
```bash
# SSH 접속
gcloud compute ssh stock-advisor --zone=asia-northeast3-a

# 앱 로그 실시간
gcloud compute ssh stock-advisor --zone=asia-northeast3-a --command="sudo docker logs -f sa-app"

# 컨테이너 상태
gcloud compute ssh stock-advisor --zone=asia-northeast3-a --command="sudo docker ps"

# 앱 health
gcloud compute ssh stock-advisor --zone=asia-northeast3-a --command="curl -s localhost:8080/actuator/health"
```

## 관리 API (SSH 터널로 안전하게)
8080은 외부에 안 열려 있으므로 터널을 뚫고 로컬에서 호출한다.
```bash
# 터널 (이 창은 유지)
gcloud compute ssh stock-advisor --zone=asia-northeast3-a -- -L 8080:localhost:8080

# 다른 터미널(로컬)에서:
curl -s localhost:8080/api/v1/admin/exit-timing | python3 -m json.tool --no-ensure-ascii        # 청산시점 분석
curl -s "localhost:8080/api/v1/admin/outcome-analysis?horizon=close" | python3 -m json.tool --no-ensure-ascii  # 승자/패자 분석
curl -s localhost:8080/api/v1/admin/strategy-report | python3 -m json.tool --no-ensure-ascii      # 전략별 수익 리포트
curl -s -X POST "localhost:8080/api/v1/admin/sync-watchlist?kospiTop=1000&kosdaqTop=500"           # 워치리스트 수동 동기화 (~20분)
curl -s -X POST "localhost:8080/api/v1/admin/scan-market?limit=30"                                 # B/C 시장 스캔 즉시
```

## 업데이트 배포
소스 변경 후 VM에 반영 (로컬에서 tar 전송 → VM에서 재빌드).
```bash
# 1) 로컬: 클린 tar 전송
cd <프로젝트 루트>
tar -czf /tmp/stock-advisor.tar.gz --exclude='./build' --exclude='./.gradle' \
  --exclude='./.idea' --exclude='./.git' --exclude='./set-env.sh' --exclude='./.env' --exclude='*.iml' .
gcloud compute scp /tmp/stock-advisor.tar.gz stock-advisor:~/stock-advisor.tar.gz --zone=asia-northeast3-a

# 2) VM: 해제 후 재빌드 (.env 는 유지됨)
gcloud compute ssh stock-advisor --zone=asia-northeast3-a --command="\
  tar -xzf ~/stock-advisor.tar.gz -C ~/stock-advisor && \
  cd ~/stock-advisor && sudo docker compose -f docker-compose.prod.yml up -d --build"
```

## 시작 / 중지 (비용 절감)
```bash
# VM 중지 (과금 거의 0 — 디스크만 청구, 데이터 보존)
gcloud compute instances stop stock-advisor --zone=asia-northeast3-a

# VM 시작 (재기동 시 컨테이너 자동 복구 — restart: unless-stopped)
gcloud compute instances start stock-advisor --zone=asia-northeast3-a

# 컨테이너만 중지/시작 (VM은 켜둔 채)
gcloud compute ssh stock-advisor --zone=asia-northeast3-a --command="cd ~/stock-advisor && sudo docker compose -f docker-compose.prod.yml stop"
gcloud compute ssh stock-advisor --zone=asia-northeast3-a --command="cd ~/stock-advisor && sudo docker compose -f docker-compose.prod.yml start"
```

## DB 점검
```bash
# 워치리스트 종목 수
gcloud compute ssh stock-advisor --zone=asia-northeast3-a --command="\
  sudo docker exec sa-postgres psql -U stockadvisor -d stockadvisor -c \"SELECT market, count(*) FROM company GROUP BY market;\""

# 가상매수/샘플 현황
gcloud compute ssh stock-advisor --zone=asia-northeast3-a --command="\
  sudo docker exec sa-postgres psql -U stockadvisor -d stockadvisor -c \"SELECT strategy, count(*) FROM trade_outcome GROUP BY strategy;\""
```

## 주의
- **반드시 단일 인스턴스 유지** — 복제/오토스케일 금지 (스케줄러·인메모리 KIS 속도제한기 때문).
- 시크릿은 VM의 `~/stock-advisor/.env` 에만 존재 (커밋·이미지 포함 금지).
- 스케줄: 공시 폴링 1분 / 신호평가 1분 / B·C 스캔 15분 / 워치리스트 동기화 평일 16:00 (Asia/Seoul).
