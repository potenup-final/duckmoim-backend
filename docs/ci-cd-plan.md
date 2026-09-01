# DuckMoim CI/CD 구축 계획

> 최초 작성 2026-08-31 · 갱신 2026-09-01
> 대상: duckmoim-backend / duckmoim-frontend
> 티켓: [STAR-20] CI/CD 파이프라인 구축

---

## 0. 진행 현황

```
✅ Phase 0   백엔드 프로젝트 골격
✅ Phase 1   CI 워크플로우           (초록불·빨간불 양방향 검증 완료)
✅ Phase 2   브랜치 보호 규칙         (실제 차단까지 확인)
✅ Phase 3a  Dockerfile             (로컬 컨테이너 헬스체크 200)
✅ Phase 5-1 이미지 빌드·GHCR 푸시   (파이프라인 검증 완료)
────────────────────────────────────────────────
⬜ Phase 4a  EC2 생성 + 수동 배포     ← 현재 병목
⬜ Phase 5-2 EC2 배포 자동화
────────────────────────────────────────────────
⬜ Phase 3b  MySQL 추가             (첫 엔티티 만들 때)
⬜ Phase 4b  도메인 + HTTPS          (프론트 붙일 때)
⬜ Phase 6   frontend CI            (후순위)
```

**병목은 EC2 하나다.** 인스턴스만 생기면 남은 작업이 반나절이면 끝난다.

### 현재 상태

| 항목 | 상태 |
|---|---|
| backend 레포 | **Public**. `chore/STAR-20` 브랜치에서 작업 중, PR #5 리뷰 대기 |
| frontend 레포 | Vercel 배포 중 (1인 작업) |
| AWS | IAM 콘솔 로그인 확보. **EC2 인스턴스 아직 없음** |
| 크레딧 | **10만원** (만료일 확인 필요) |
| 협업 | backend 3명 / frontend 1명 (갤럭시북 2 · 맥북 2) |
| 자동화 | 티켓 `진행 중` → GitHub 이슈·브랜치 자동 생성 |

> ⚠️ **main 에 아직 프로젝트 골격이 없다.** PR #5 가 머지돼야 백엔드 3명이 기능 개발을 시작할 수 있다.

---

## 1. 확정된 구성

| 항목 | 값 | 근거 |
|---|---|---|
| 언어 | Java 17 (Temurin) | — |
| 프레임워크 | **Spring Boot 3.5.16** | 아래 참고 |
| 빌드 | Gradle 9.7.1 (래퍼 동봉) | 커밋 훅이 이미 `build.gradle` 참조 |
| 패키지 | `com.duckmoim` | 저장소 개명 반영 |
| 레지스트리 | **GHCR** | 아래 참고 |
| 인스턴스 | **t3.small** (2GB) | 아래 비용 계산 |
| 서버 대수 | **1대** | dev 는 각자 노트북이 담당 |
| DB | **A안** — EC2 안 docker compose | RDS 는 크레딧 초과 |

### Spring Boot 3.5.16 을 쓰는 이유

start.spring.io 는 이제 **4.0.0 이상만 생성해 준다.** 그럼에도 3.5 를 택한 것은 국내 학습자료 대부분이 Boot 3 기준이라 팀원이 참고자료를 그대로 쓸 수 있기 때문이다. 골격만 4.0.8 로 받아 스타터 이름(`spring-boot-starter-webmvc` → `spring-boot-starter-web`)과 플러그인 버전을 3.5 에 맞춰 고쳤다.

### 레지스트리를 GHCR 로 정한 이유

| | Docker Hub | ECR | **GHCR** |
|---|---|---|---|
| 등록할 시크릿 | 2개 | AWS 자격증명 또는 OIDC | **0개** |
| 사전 준비 | 계정·토큰 발급 | 리포지토리 + IAM 정책 + OIDC Provider + Role | **없음** |
| EC2 인증 | 필요 | IAM Role 필요 | **Public 이면 불필요** |

`GITHUB_TOKEN` 이 실행마다 자동 발급되고 작업이 끝나면 폐기된다. 사람이 토큰을 만들고 갱신할 일이 없다.

ECR 의 이점(ECS/EKS 연동, VPC 내부 pull, 이미지 스캔)은 전부 AWS 생태계에 깊이 들어갔을 때 발동한다. EC2 한 대에 `docker run` 하는 구성에서는 하나도 해당되지 않는다.

### JPA·MySQL 드라이버를 일부러 뺐다

클래스패스에 있으면 DataSource 자동설정이 접속 정보를 찾다가 **기동 자체가 실패한다.** 코드를 한 줄도 안 짜도 그렇다.

```
Failed to configure a DataSource: 'url' attribute is not specified
and no embedded datasource could be configured.
```

DB 없이 컨테이너만 EC2 에 올려 배포 경로부터 검증하려는 의도다. **첫 엔티티를 만드는 사람이 MySQL 과 함께 추가한다.**

---

## 2. 아키텍처

### 지금 (Phase 3a 까지)

```
   [GitHub Actions]                    [GHCR]
    test → image  ──── push ────>  duckmoim-backend:sha-…
                                          │
                                          │ (아직 받아갈 서버가 없음)
                                          ▼
                                        ( ? )
```

### 목표

```
                        사용자 브라우저
              ┌───────────────┴───────────────┐
        정적 페이지                        API 호출
              ▼                               ▼
      ┌───────────────┐        ┌──────────────────────────────┐
      │    Vercel     │        │  AWS EC2 (t3.small)          │
      │  (frontend)   │─CORS──>│  ┌────────┐                  │
      │   현행 유지    │ HTTPS  │  │ Caddy  │ HTTPS 자동  🟢    │
      └───────────────┘        │  └───┬────┘                  │
                               │  ┌───▼─────────┐             │
        [GHCR] ──docker pull──>│  │ Spring Boot │ 🔄 매 배포   │
                               │  └───┬─────────┘             │
                               │  ┌───▼────┐  ┌──────────┐    │
                               │  │ MySQL  │─>│  volume  │ 🟢 │
                               │  └────────┘  └──────────┘    │
                               └──────────────────────────────┘
```

**frontend 는 Vercel 에 그대로 둔다.** EC2 로 옮기면 CDN·preview 배포·무료 호스팅을 잃고 nginx 설정을 직접 하게 된다. 순수 손해다.

### ⭐ 배포 단위 ≠ 실행 환경

CD 가 매 배포마다 교체하는 것은 **Spring Boot 컨테이너 하나뿐이다.**

| | 대상 | 배포 시 |
|---|---|---|
| 🔄 배포 단위 | Spring Boot | 새 이미지로 교체 |
| 🟢 실행 환경 | MySQL, Caddy | **건드리지 않음** |

```bash
docker compose pull app
docker compose up -d app     # ← app 만. 인자 없이 up -d 하면 MySQL 까지 재시작된다
```

volume 설정이 잘못돼 있는데 MySQL 을 재시작하면 **데이터가 사라진다.** 초보 CD 에서 가장 위험한 실수다.

---

## 3. 관통 원칙

### ① 손으로 한 번 성공한 것만 자동화한다

CD 워크플로우는 새로운 것을 만드는 작업이 아니라 **이미 성공한 수동 절차를 받아적는 작업**이다. 손으로 배포해본 적 없이 자동화하면 실패했을 때 원인이 *배포 방법*인지 *Actions 설정*인지 구분이 안 된다.

### ② 한 번에 하나씩 추가하고, 매번 초록불을 확인한다

스텝 10개를 한 번에 써놓고 돌리지 않는다. 이 원칙 덕분에 실제로 두 번 이득을 봤다 — alpine 아키텍처 문제와 buildx 드라이버 문제를 각각 독립적으로 잡았다.

### ③ CI 먼저, CD 나중

CI 없는 CD 는 *검증 안 된 코드를 자동으로 서버에 밀어넣는 기계*다. 그래서 `image` 잡에 `needs: test` 를 걸었고, 워크플로우를 한 파일로 합쳤다.

---

## 4. 단계별 상세

### ✅ Phase 0 — 백엔드 프로젝트 골격

- [x] Spring Boot 3.5.16 / Java 17 / Gradle 스캐폴딩
- [x] 의존성은 `Spring Web`, `Validation` **만**
- [x] `GET /api/health` → `{"status":"UP"}`
- [x] 테스트 2개 — 컨텍스트 로딩(`@SpringBootTest`), 헬스체크 응답(`@WebMvcTest`)
- [x] `installGitHooks` 태스크 — 빌드가 `core.hooksPath` 를 대신 설정
- [x] `gradlew` 실행 권한 `100755` 유지
- [x] 패키지명 `com.duckmoim` 으로 정리

**헬스체크는 DB 등 외부 의존을 일부러 건드리지 않는다.** "이 프로세스가 살아 있다"는 의미만 유지해야 배포 실패 시 원인을 좁힐 수 있다.

**완료 조건 ✅** 새로 클론해서 `./gradlew build` 하면 통과한다.

---

### ✅ Phase 1 — CI

- [x] 워크플로우 작성 (현재 `ci-cd.yml` 의 `test` 잡)
- [x] 트리거: `pull_request` + `push` (main 대상)
- [x] 체크아웃 → JDK 17(Gradle 캐시) → `./gradlew build`
- [x] `concurrency` — PR 은 이전 실행 취소, **main 은 취소하지 않음**
- [x] 실패 시에만 테스트 리포트 아티팩트 업로드
- [x] **정상 PR 에서 초록불 확인** (1m 3s)
- [x] **일부러 테스트를 깨뜨려 빨간불 확인**

**②를 반드시 해야 한다.** 초록불만 보면 "사실 아무것도 검사하지 않는 CI" 를 몇 주간 믿고 쓰게 된다. 실제로 기대값을 `UP` → `DOWN` 으로 바꿔 실패시키고, 로그에 `HealthControllerTest.java:24` 까지 표시되는 것과 `if: failure()` 스텝이 그때만 실행되는 것을 확인한 뒤 되돌렸다.

**완료 조건 ✅** 양방향 모두 검증됨.

---

### ✅ Phase 2 — 브랜치 보호 규칙

- [x] **레포를 Public 으로 전환**
- [x] 히스토리 비밀정보 스캔 — 깨끗
- [x] Ruleset `main` / Enforcement **Active**
- [x] main 직접 push 금지 / PR 필수 / 승인 1명 / 필수 체크 `빌드 및 테스트`
- [x] force push·삭제 차단
- [x] **실제 차단 확인** — PR #5 가 `BLOCKED / REVIEW_REQUIRED`

**⚠️ 무료 플랜은 Private 레포에 보호 규칙을 적용할 수 없다.** 처음에 규칙을 만들었는데 PR 이 그대로 머지 가능한 상태였고, API 가 `Upgrade to GitHub Pro or make this repository public` 을 반환해 원인을 찾았다. Public 전환으로 해결했고, 덤으로 **Actions 사용 분이 무제한**이 됐다.

**⚠️ 잡 이름 `빌드 및 테스트` 를 바꾸면 안 된다.** 규칙이 이 이름을 참조하므로, 바꾸면 존재하지 않는 체크를 기다리며 모든 PR 이 머지 불가가 된다. 파일 이름은 바꿔도 안전하다.

---

### ✅ Phase 3a — Dockerfile

- [x] 멀티스테이지 `Dockerfile` (JDK 빌드 → JRE 실행)
- [x] `.dockerignore`
- [x] 의존성 다운로드를 소스 복사보다 아래층에 배치 (캐시)
- [x] 비 root 사용자로 실행
- [x] `-XX:MaxRAMPercentage=70`
- [x] **로컬 빌드 → `docker run` → 헬스체크 200**

이미지 크기 **489MB**. JRE 베이스 레이어는 EC2 에 한 번만 받으면 캐시되고, 이후 배포는 바뀐 jar 레이어(~25MB)만 받는다.

**⚠️ alpine 을 쓸 수 없다.** `eclipse-temurin` 의 alpine 변종은 **amd64 만 배포**해서 애플 실리콘에서 `no match for platform in manifest` 로 빌드가 죽는다. 일반 태그는 amd64·arm64 를 모두 지원해 팀원 전원(갤럭시북 2 · 맥북 2)이 같은 Dockerfile 로 빌드할 수 있다.

---

### ✅ Phase 5-1 — 이미지 빌드 및 GHCR 푸시

- [x] `ci.yml` + `cd.yml` 을 **`ci-cd.yml`** 하나로 합침
- [x] `image` 잡에 `needs: test` — 테스트 통과 후에만 실행
- [x] `packages: write` 를 `image` 잡에만 부여
- [x] GHCR 로그인 (`GITHUB_TOKEN` 자동)
- [x] 태그: 커밋 SHA 항상 + `latest` 는 기본 브랜치에서만
- [x] 레이어 캐시 `type=gha`
- [x] **GHCR 에 이미지 생성 확인**

**파일을 나누면 안 되는 이유:** 별도 워크플로우는 서로 모른 채 병렬로 돈다. 테스트가 실패해도 이미지가 올라가고, 배포 잡을 붙이면 검증 안 된 코드가 EC2 로 나간다. `needs:` 는 같은 파일 안의 잡끼리만 걸 수 있다. 두 워크플로의 트리거가 애초에 동일했다는 점도 합치는 근거다.

**⚠️ `Buildx 준비` 스텝이 필요하다.** 러너의 기본 빌더는 `docker` 드라이버인데 캐시 내보내기를 지원하지 않아 이렇게 죽는다.

```
ERROR: Cache export is not supported for the docker driver.
```

`docker/setup-buildx-action` 이 `docker-container` 드라이버 빌더를 만들어 준다.

---

### ⬜ Phase 4a — EC2 생성 및 수동 배포 ⭐

**전체 계획에서 가장 중요한 단계.** 여기를 건너뛰면 Phase 5-2 실패 시 원인을 특정할 수 없다.

인프라 (AWS 콘솔):
- [ ] 키페어 생성 → `.pem` 보관 + `chmod 400`
- [ ] 인스턴스 생성 — Ubuntu 22.04 LTS / **t3.small** / `ap-northeast-2`
- [ ] 보안그룹
      | 포트 | 소스 | 용도 |
      |---|---|---|
      | 22 | 팀 IP | SSH |
      | 8080 | 0.0.0.0/0 | **임시** — Phase 4b 에서 443 으로 교체 |
- [ ] 탄력적 IP 할당·연결

서버 세팅:
- [ ] `sudo apt install -y docker.io` / `usermod -aG docker ubuntu`
- [ ] 팀원 SSH 공개키를 `~/.ssh/authorized_keys` 에 등록

배포:
- [ ] **GHCR 패키지를 Public 으로 전환** (Package settings → Change visibility)
- [ ] `docker pull ghcr.io/potenup-final/duckmoim-backend:latest`
- [ ] `docker run -d -p 8080:8080 --name duckmoim-app …`

**완료 조건**
> `curl http://<탄력적IP>:8080/api/health` 가 `{"status":"UP"}` 을 반환한다.

**⚠️ 아키텍처 주의.** 맥에서 만든 이미지는 arm64 라 t3.small(amd64)에서 안 돈다. Actions 러너가 amd64 이므로 **GHCR 의 이미지를 받아 쓰면 이 문제가 없다.** 로컬 빌드 이미지를 옮기려 하지 말 것.

**⚠️ SSH 키 정책.** `.pem` 개인키를 단톡방에 공유하지 않는다. 각자 `ssh-keygen -t ed25519` 후 공개키만 공유하면, 최초 생성자만 `.pem` 을 보관하고 개별 회수도 가능하다.

---

### ⬜ Phase 5-2 — EC2 배포 자동화

- [ ] GitHub Secrets 등록 — `EC2_HOST`, `EC2_USER`, `EC2_SSH_KEY`
- [ ] `deploy` 잡 추가 — `needs: image`, `if: github.ref == 'refs/heads/main'`
- [ ] SSH → `docker pull` → `docker stop/rm` → `docker run`
- [ ] 롤백 절차를 README 에 기록

`if:` 조건이 필요한 이유: 트리거는 파일 단위라 PR 에서도 워크플로우가 돈다. 배포 잡만 main 으로 제한해야 한다.

**완료 조건**
> 사소한 변경을 main 에 머지하면 3분 내에 EC2 에 반영된다.

---

### ⬜ Phase 3b — MySQL 추가

**첫 엔티티를 만들 때 진행한다.**

- [ ] `spring-boot-starter-data-jpa` + `mysql-connector-j` 추가
- [ ] `docker-compose.yml` — app + MySQL
- [ ] **MySQL volume 설정** — 없으면 컨테이너 재생성 시 데이터 소실
- [ ] DB 접속 정보를 환경변수로 분리 (나중에 RDS 이전 대비)
- [ ] CD 를 `docker compose up -d app` 으로 전환

**⚠️ 이미지에 비밀정보를 굽지 않는다.** GHCR 패키지가 Public 이므로 `ENV DB_PASSWORD=…` 를 Dockerfile 에 넣으면 누구나 볼 수 있다. 반드시 `docker run -e` 또는 compose 의 환경변수로 주입한다.

---

### ⬜ Phase 4b — 도메인 + HTTPS

**프론트를 붙일 때 진행한다.**

- [ ] 도메인 확보 → 탄력적 IP 로 A 레코드
- [ ] Caddy 리버스 프록시 → 인증서 자동 발급
- [ ] 보안그룹에서 8080 닫고 80·443 열기
- [ ] 백엔드 CORS 에 Vercel 도메인 허용

**⚠️ 혼합 콘텐츠.** Vercel(`https://`)에서 EC2 를 맨 IP(`http://…:8080`)로 호출하면 **브라우저가 요청 자체를 차단한다.** 부트캠프 팀이 거의 예외 없이 여기서 하루를 날린다.

---

### ⬜ Phase 6 — frontend CI

Vercel 이 이미 PR 마다 클린 빌드 + preview 배포를 제공하므로 **우선순위가 낮다.**

- [ ] `tsc --noEmit` + `eslint`
- [ ] 브랜치 보호 연결 (**해당 레포도 Public 전환 필요**)
- [ ] 승인 리뷰는 **0명** — 1인 작업이라 자기 PR 을 자기가 승인할 수 없다

---

## 5. 비용

서울 리전 · 24시간 가동 · EBS 8GB 포함 · 크레딧 10만원 기준

| 구성 | 월 | 2개월 | 크레딧 소진 |
|---|---|---|---|
| t3.micro (1GB) | $10.2 | 2.9만원 | 29% |
| **t3.small (2GB)** | **$19.7** | **5.6만원** | **56%** ✅ |
| t3.medium (4GB) | $38.7 | 11.1만원 | 초과 ❌ |
| t3.small + RDS | ~$41 | 11.7만원 | 초과 ❌ |

**t3.small 을 고른 이유는 메모리다.**

```
Ubuntu 200MB + Docker 100MB + Spring Boot 500MB  =  800MB
+ MySQL 400MB                                    = 1.2GB   ← t3.micro(1GB) 초과
```

t3.micro 로 시작하면 MySQL 을 올리는 순간 어차피 올려야 한다. 차액이 2개월에 2.7만원이라 처음부터 t3.small 이 낫다.

**RDS 는 크레딧을 넘긴다.** 이것이 DB A안(docker compose)을 택한 예산 근거다.

> 탄력적 IP 는 인스턴스에 붙어 있으면 무료, 떼어두면 월 ~$3.6. 아웃바운드 트래픽은 월 100GB 까지 무료.

---

## 6. GitHub Secrets

`Settings → Secrets and variables → Actions`

| 이름 | 용도 | 등록 시점 |
|---|---|---|
| `EC2_HOST` | 탄력적 IP | Phase 5-2 |
| `EC2_USER` | `ubuntu` | Phase 5-2 |
| `EC2_SSH_KEY` | `.pem` 전문 (`BEGIN` ~ `END`) | Phase 5-2 |
| `DB_PASSWORD` | MySQL 비밀번호 | Phase 3b |

**레지스트리 관련 시크릿은 없다.** GHCR 은 `GITHUB_TOKEN` 이 자동 처리한다. Docker Hub 를 썼다면 `DOCKER_HUB_USERNAME`·`DOCKER_HUB_ACCESS_TOKEN` 을 등록하고 갱신까지 관리해야 했다.

> Secrets 는 코드에 넣지 않는다. `${{ secrets.NAME }}` 으로 이름만 참조하고, 실행 로그에서는 `***` 로 마스킹된다.

---

## 7. 알려진 함정

작업 중 막히면 여기부터 확인한다. **★ 표시는 이번 구축에서 실제로 겪은 것.**

| 증상 | 원인 | 해결 |
|---|---|---|
| ★ `no match for platform in manifest` | `eclipse-temurin` alpine 은 amd64 전용 | 일반 태그 사용 |
| ★ `Cache export is not supported for the docker driver` | 러너 기본 빌더가 `docker` 드라이버 | `docker/setup-buildx-action` 추가 |
| ★ 규칙을 만들었는데 PR 이 그냥 머지됨 | 무료 플랜은 Private 레포에 보호 규칙 적용 불가 | 레포를 Public 으로 |
| ★ GHCR 이미지를 인증 없이 못 받음 | 패키지 기본값이 Private | Package settings → Public |
| ★ Initializr 가 Boot 3 을 안 만들어 줌 | 4.0.0 이상만 제공 | 4.0.8 로 받아 플러그인 버전·스타터 이름 수정 |
| `./gradlew: Permission denied` | 실행 권한 없이 커밋됨 | `git update-index --chmod=+x gradlew` |
| `Bind for 0.0.0.0:8080 failed` | IDE 가 8080 을 점유 중 | IDE 정지 또는 `-p 9000:8080` |
| 로컬은 되는데 리눅스에서 클래스 못 찾음 | macOS 는 파일명 대소문자 미구분 | 대소문자 일치 확인 |
| Mixed Content 차단 | HTTPS 페이지에서 HTTP API 호출 | 백엔드에 도메인 + HTTPS |
| CORS 에러 | Vercel 도메인 미허용 | `allowedOrigins` 추가 |
| EC2 에서 앱이 갑자기 죽음 | 메모리 부족(OOM) | 인스턴스 상향 / swap / `MaxRAMPercentage` |
| 배포마다 DB 데이터 초기화 | volume 미설정 + MySQL 재시작 | volume 설정 후 `up -d app` |
| 필수 체크 목록에 잡이 없음 | 한 번도 실행된 적 없음 | PR 을 한 번 돌린 뒤 설정 |
| 커밋에 Jira 키가 안 붙음 | 훅 미활성화 | `./gradlew build` 한 번 (`installGitHooks`) |
| 스케줄이 엉뚱한 시간에 실행 | cron 이 UTC 기준 | KST = UTC + 9 |

---

## 8. 다음 액션

### 지금 바로

1. **GHCR 패키지를 Public 으로 전환** — Packages → duckmoim-backend → Package settings → Change visibility
2. **팀원에게 t3.small·GHCR·1대 확정 통보**
3. **PR #5 리뷰 요청** — main 에 골격이 없어 팀 전체가 대기 중

### EC2 확보 후

4. Phase 4a — 인스턴스 생성 → 손으로 `pull` + `run` → 헬스체크 200
5. Secrets 3개 등록
6. Phase 5-2 — `deploy` 잡 추가

### 확인 대기

- 운영진 — 크레딧 만료일
- 팀원 — SSH 공개키 수집

---

## 부록 — 워크플로우 구조

```
.github/workflows/ci-cd.yml

on: pull_request + push (main)

jobs:
  test                          "빌드 및 테스트"    ← 브랜치 보호가 참조하는 이름
    체크아웃 → JDK 17 → gradlew build → (실패 시 리포트)
      │
      │ needs
      ▼
  image                         "이미지 빌드 및 푸시"
    체크아웃 → Buildx → GHCR 로그인 → 태그 생성 → 빌드·푸시
      │
      │ needs  (Phase 5-2 에서 추가)
      ▼
  deploy                        "EC2 배포"
    if: main 일 때만
    SSH → docker pull → docker run
```
