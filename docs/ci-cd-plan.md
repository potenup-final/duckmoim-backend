# YouStar CI/CD 구축 계획

> 작성일: 2026-08-31
> 대상: youstar-backend / youstar-frontend

---

## 0. 전제

### 현재 상태

| 항목 | 상태 |
|---|---|
| backend 레포 | 애플리케이션 코드 **없음**. Jira 자동화 워크플로우 2개 + PR 템플릿만 존재 |
| frontend 레포 | Vercel 배포 중 (1인 작업) |
| AWS | IAM 계정 확보 완료. EC2 인스턴스는 **아직 없음** |
| 협업 | backend 3명 / frontend 1명, Jira 스프린트 진행 중 |
| 기존 자동화 | 티켓이 `진행 중`으로 이동 → GitHub 이슈·브랜치 자동 생성 |

### 스택

| 항목 | 값 | 확정도 |
|---|---|---|
| 빌드 도구 | **Gradle 9.7.1** (래퍼 동봉) | ✅ 확정 |
| 언어 | **Java 17** (Temurin) | ✅ 확정 |
| 프레임워크 | **Spring Boot 3.5.16** | ✅ 확정 |
| DB | MySQL 8 | ⚠️ 확인 필요 (아직 의존성에 없음) |
| 배포 | Docker 이미지 → EC2 단일 인스턴스 | ⚠️ 확인 필요 |

> **Spring Boot 3.5.16 을 쓰는 이유**
>
> start.spring.io 는 이제 **4.0.0 이상만 생성해 준다.** 그럼에도 3.5 를 택한 것은,
> 국내 학습자료와 강의 대부분이 Boot 3 기준이라 팀원 3명이 참고자료를 그대로
> 쓸 수 있기 때문이다. 골격만 4.0.8 로 받아 스타터 이름(`spring-boot-starter-webmvc`
> → `spring-boot-starter-web`)과 플러그인 버전을 3.5 에 맞춰 고쳤다.
>
> Gradle 9.7.1 + Boot 3.5.16 + Java 17 조합은 로컬 빌드로 검증했다.

> **JPA·MySQL 드라이버는 아직 넣지 않았다**
>
> 클래스패스에 있으면 DataSource 자동설정이 접속 정보를 찾다가 **기동 자체가
> 실패한다.** 아무 코드를 안 짜도 그렇다.
>
> ```
> Failed to configure a DataSource: 'url' attribute is not specified
> and no embedded datasource could be configured.
> ```
>
> DB 없이 컨테이너만 EC2 에 올려보는 첫 배포를 위해 일부러 뺐다.
> 첫 엔티티를 만들 때 MySQL 과 함께 추가한다.

> **스택이 왜 지금 필요한가**
>
> 이 문서의 *순서와 원칙*은 스택과 무관하다. 하지만 *구현*은 100% 스택에 종속된다.
>
> | 스택 | CI에 들어갈 내용 |
> |---|---|
> | Java + Gradle | `setup-java` → `./gradlew build` |
> | Node + NestJS | `setup-node` → `npm ci && npm test` |
> | Python + Django | `setup-python` → `pip install && pytest` |
>
> 공통되는 줄이 하나도 없다. Dockerfile 역시 베이스 이미지·빌드 산출물 경로가 전부 달라진다.
> **Phase 0부터는 스택 확정 없이 한 줄도 쓸 수 없다.**

---

## 1. 목표 아키텍처

```
                        사용자 브라우저
                              │
              ┌───────────────┴───────────────┐
              │                               │
        정적 페이지                        API 호출
              │                               │
              ▼                               ▼
      ┌───────────────┐              ┌──────────────────────────────┐
      │    Vercel     │              │  AWS EC2                     │
      │  (frontend)   │─── CORS ────>│                              │
      │   현행 유지    │   HTTPS      │  ┌────────┐                  │
      └───────────────┘              │  │ Caddy  │  HTTPS 자동      │
                                     │  └───┬────┘  🟢 상주         │
                                     │      │                       │
                                     │  ┌───▼─────────┐             │
                                     │  │ Spring Boot │  🔄 매 배포  │
                                     │  └───┬─────────┘     교체     │
                                     │      │                       │
                                     │  ┌───▼────┐   ┌──────────┐   │
                                     │  │ MySQL  │──>│  volume  │   │
                                     │  └────────┘   │ (데이터)  │   │
                                     │   🟢 상주      └──────────┘   │
                                     └──────────────────────────────┘
                                        docker compose (3개 서비스)
```

**frontend는 Vercel에 그대로 둔다.** EC2로 옮기면 CDN·preview 배포·무료 호스팅을 잃고 nginx 설정을 직접 하게 된다. 순수 손해.

### ⭐ 배포 단위 ≠ 실행 환경

CD가 **매 배포마다 교체하는 것은 Spring Boot 컨테이너 하나뿐이다.**

| | 대상 | 배포 시 |
|---|---|---|
| 🔄 배포 단위 | Spring Boot | 새 이미지로 교체 |
| 🟢 실행 환경 | MySQL, Caddy | **건드리지 않음** |

```bash
docker compose pull app
docker compose up -d app     # ← app 만. MySQL·Caddy 는 그대로 둔다
```

`docker compose up -d` 를 인자 없이 실행하면 MySQL까지 재시작된다. 커넥션이 끊기고, volume 설정이 잘못돼 있으면 **데이터가 사라진다.** 초보 CD에서 가장 위험한 실수다.

`docker-compose.yml` 이 세 서비스를 한 파일에 기술하는 것은 *구성을 한곳에 모으기 위함*이지, 셋을 늘 함께 재시작한다는 뜻이 아니다.

---

## 1-1. DB를 어디에 둘 것인가

| | 방식 | 장점 | 단점 |
|---|---|---|---|
| **A** | EC2 안 docker compose | 무료, 즉시 가능, 로컬과 동일 구성 | 메모리 압박, EC2 소실 시 데이터 소실, 백업 수동 |
| **B** | RDS (관리형) | 자동 백업, EC2 메모리 여유, 앱과 수명 분리 | 프리티어 소진 여부 불확실, IAM에 RDS 권한 필요 |
| **C** | EC2에 MySQL 직접 설치 | — | 도커를 쓰면서 하나만 수동 관리. **비권장** |

**제안: A로 시작한다.**

부트캠프 공용 AWS 계정이면 RDS 프리티어가 이미 소진됐을 수 있고, 발급받은 IAM에 RDS 권한이 없을 수도 있다. A는 그런 불확실성 없이 오늘 착수할 수 있다.

나중에 B로 옮기는 비용도 작다 — **애플리케이션이 DB 접속 정보를 환경변수로만 읽고 있다면**, 주소를 바꾸고 데이터를 덤프·복원하면 끝이다. (그래서 Phase 3의 "설정을 환경변수로 분리"가 중요하다.)

> ⚠️ **A를 택하면 volume 설정이 필수다.** 빼먹으면 컨테이너를 재생성할 때마다 DB가 초기화된다.

---

## 2. 관통 원칙

이 세 가지를 어기면 반드시 시간을 날린다.

### ① 손으로 한 번 성공한 것만 자동화한다

CD 워크플로우는 새로운 것을 만드는 작업이 아니라, **이미 성공한 수동 절차를 받아적는 작업**이다.
손으로 배포해본 적 없는 상태에서 자동화를 시도하면, 실패했을 때 원인이 *배포 방법*인지 *Actions 설정*인지 구분이 안 된다. 변수가 둘이라 디버깅 비용이 폭증한다.

### ② 한 번에 하나씩 추가하고, 매번 초록불을 확인한다

워크플로우에 스텝 10개를 한 번에 써놓고 돌리지 않는다.
`빌드만 → 초록불 → 테스트 추가 → 초록불` 순으로 간다.

### ③ CI 먼저, CD 나중

CI 없는 CD는 *검증 안 된 코드를 자동으로 서버에 밀어넣는 기계*다.
CD는 배포 속도를 올리는 장치이고, 검증이 없으면 사고 나는 속도만 빨라진다.

---

## 3. 단계별 계획

```
✅ Phase 0  앱 코드 골격         ─┐
✅ Phase 1  CI 워크플로우         │ AWS 불필요
⬜ Phase 2  브랜치 보호 규칙      │
⬜ Phase 3  Docker화             ─┘
──────────────────────────────────
⬜ Phase 4  EC2 수동 배포        ─┐
⬜ Phase 5  CD 워크플로우         │ AWS 필요
──────────────────────────────────┘
⬜ Phase 6  frontend CI           후순위
```

> Phase 3 은 **3a(Dockerfile + `docker run`)** 와 **3b(compose + MySQL)** 로 나눠
> 진행한다. 첫 배포는 컨테이너 하나만 올려 파이프라인 경로부터 검증하고, MySQL 과
> Caddy 는 실제로 필요해지는 시점에 더한다. 원칙 ②(한 번에 하나씩)에 따른 것이다.

---

### Phase 0 — 백엔드 프로젝트 골격

**목표:** CI가 검사할 대상을 만든다. 지금 코드가 0줄이라 여기가 전체의 블로커다.

**작업**

- [x] 프로젝트 생성 — 의존성: `Spring Web`, `Validation` **만**
      (JPA·MySQL·Lombok 은 필요해질 때 추가. 위 스택 항목 참고)
- [x] `.gitignore` (`build/`, `.gradle/`, `.env`, `.DS_Store`)
- [x] 헬스체크 엔드포인트 `GET /api/health` → `200 {"status":"UP"}`
- [x] 테스트 2개 — 컨텍스트 로딩, 헬스체크 응답 검증
- [x] `installGitHooks` 태스크 — 빌드가 `core.hooksPath` 를 대신 설정
- [x] `gradlew` 실행 권한 유지한 채 커밋 (`100755` 확인)
- [ ] 로컬 개발용 MySQL compose — Phase 3b 로 미룸

**⚠️ 함정 — `gradlew` 실행 권한**

macOS/Windows에서 생성하면 `gradlew`가 실행 권한 없이 커밋되어, CI 리눅스 머신에서 `Permission denied`로 죽는다. 이걸로 첫 CI에서 대부분 한 번 막힌다.

```bash
git update-index --chmod=+x gradlew
git commit -m "chore: gradlew 실행 권한 부여"
```

**완료 조건**
> 팀원이 레포를 새로 클론해서 `./gradlew test` 를 실행하면 통과한다.

**예상:** 0.5일

---

### Phase 1 — CI 워크플로우

**목표:** PR을 열면 자동으로 빌드·테스트가 돌고, 결과가 PR 화면에 표시된다.

**작업**

- [x] `.github/workflows/ci.yml` 생성
- [x] 트리거: `pull_request` (main 대상) + `push` (main)
- [x] 스텝: 체크아웃 → JDK 17 설치(Gradle 캐시 포함) → `./gradlew build`
- [x] `concurrency` — PR 에 새 커밋이 오면 이전 실행 취소. **main 은 취소하지 않음**
      (머지된 커밋의 검증 이력이 남아야 어느 커밋부터 깨졌는지 추적 가능)
- [x] 실패 시에만 테스트 리포트를 아티팩트로 업로드
- [ ] **일부러 깨뜨린 PR 에서 ❌ 뜨는지 확인** ← 아직 미검증

**완료 조건**
> 성공만 확인하지 말 것.
> 1. 정상 PR에서 ✅ 가 뜬다
> 2. **일부러 테스트를 깨뜨린 PR에서 ❌ 가 뜬다** ← 이걸 확인해야 CI가 실제로 작동하는 것

②를 건너뛰면 "사실 아무것도 검사하지 않는 CI"를 몇 주간 믿고 쓰는 일이 생긴다.

**예상:** 0.5일

---

### Phase 2 — 브랜치 보호 규칙

**목표:** CI 결과에 강제력을 부여한다. 이걸 안 켜면 빨간불이어도 머지가 가능해서 Phase 1이 무의미해진다.

**작업 (GitHub 웹 UI — `Settings → Rules → Rulesets`)**

backend:
- [ ] `main` 직접 push 금지
- [ ] 머지 전 PR 필수
- [ ] **Require status checks to pass** → Phase 1의 CI job 선택
- [ ] 승인 리뷰 1명 이상
- [ ] 머지 후 브랜치 자동 삭제

frontend (1인 작업):
- [ ] 승인 리뷰는 **0명** — 자기 PR은 자기가 승인할 수 없어 영구 차단된다
- [ ] 나머지는 동일

**⚠️ 순서 주의:** status check 목록에는 **한 번이라도 실행된 적 있는 job만** 나타난다. Phase 1을 먼저 완료하고 PR을 한 번 돌린 뒤에 설정해야 한다.

**예상:** 20분

---

### Phase 3 — Docker화

**목표:** "어느 머신에서든 똑같이 도는 배포 단위"를 확보한다. 이게 있어야 EC2에 올릴 것이 생긴다.

**작업**

- [ ] `Dockerfile` — 멀티스테이지 (빌드 stage + 실행 stage 분리, 이미지 크기 대폭 감소)
- [ ] `.dockerignore` (`build/`, `.git/`, `.gradle/`)
- [ ] `docker-compose.yml` — app + MySQL + Caddy
- [ ] **MySQL volume 설정** — 없으면 컨테이너 재생성 시 데이터가 사라진다
- [ ] JVM 메모리 옵션: `-XX:MaxRAMPercentage=70` ← **아래 함정 참고**
- [ ] 설정을 환경변수로 분리 (DB 주소·비밀번호를 코드에 넣지 않는다)
      → 나중에 RDS로 이전할 때 주소만 교체하면 되도록

**⚠️ 함정 — EC2 메모리**

프리티어 `t3.micro`는 RAM이 1GB다. Spring Boot + MySQL을 한 인스턴스에 같이 띄우면 **OOM으로 죽는다.**
- 크레딧이 있다면 `t3.small`(2GB) 이상 권장
- `t3.micro`를 써야 한다면 swap 2GB를 잡거나, DB를 RDS로 분리

**완료 조건**
> 로컬에서 `docker compose up` → `curl localhost:8080/api/health` 가 200을 반환한다.

**예상:** 1일

---

### Phase 4 — EC2 수동 배포 ⭐

**목표:** 손으로 배포를 한 번 성공시킨다. **전체 계획에서 가장 중요한 단계다.**

여기를 건너뛰고 Phase 5로 가면 실패 원인을 특정할 수 없어 시간을 크게 날린다.

**작업**

인프라:
- [ ] EC2 인스턴스 생성 (Ubuntu 22.04, `t3.small` 권장, 리전 `ap-northeast-2`)
- [ ] 키페어 발급 후 **안전하게 보관** (재발급 불가)
- [ ] 보안그룹
      | 포트 | 소스 | 용도 |
      |---|---|---|
      | 22 | 내 IP만 | SSH |
      | 80 | 0.0.0.0/0 | HTTP (HTTPS 리다이렉트용) |
      | 443 | 0.0.0.0/0 | HTTPS |
      > 8080을 외부에 직접 열지 않는다. Caddy가 앞단에서 받는다.
- [ ] 탄력적 IP(Elastic IP) 할당 — 없으면 재시작 때마다 주소가 바뀐다

서버 세팅:
- [ ] Docker + Docker Compose 설치
- [ ] 도메인 확보 후 EC2 IP로 A 레코드 연결
- [ ] Caddy로 리버스 프록시 구성 → HTTPS 인증서 자동 발급

배포:
- [ ] 이미지를 손으로 빌드·실행하고 동작 확인
- [ ] frontend에서 실제 API 호출 성공 확인 (CORS 설정 포함)

**⚠️ 함정 — HTTPS 혼합 콘텐츠**

Vercel(`https://`)에서 EC2를 맨 IP(`http://12.34.56.78:8080`)로 호출하면 **브라우저가 요청 자체를 차단한다.** 부트캠프 팀이 거의 예외 없이 여기서 하루를 날린다.
백엔드에도 도메인 + HTTPS가 반드시 필요하다. Caddy를 쓰면 인증서 발급·갱신이 자동이라 nginx보다 훨씬 빠르다.

**완료 조건**
> 브라우저에서 `https://api.<도메인>/api/health` 가 200을 반환하고,
> Vercel의 frontend에서 이 API를 호출했을 때 CORS 에러 없이 응답을 받는다.

**예상:** 1~2일 (도메인 DNS 전파 대기 포함)

---

### Phase 5 — CD 워크플로우

**목표:** main에 머지되면 자동으로 EC2에 반영된다.

Phase 4에서 손으로 친 명령어를 `.yml`로 옮기는 작업이다. 새로 배울 것은 거의 없다.

**작업**

- [ ] 이미지 레지스트리 결정 → **GHCR(GitHub Container Registry) 권장**
      - `GITHUB_TOKEN`으로 바로 인증되어 AWS 권한이 추가로 필요 없다
      - ECR은 IAM 권한 설정이 더 번거롭다
- [ ] GitHub Secrets 등록 (아래 표 참고)
- [ ] `.github/workflows/cd.yml` 생성
      - 트리거: `push` to `main`
      - 이미지 빌드 → GHCR 푸시 → SSH로 EC2 접속 → `docker compose up -d app`
      - ⚠️ **`app` 서비스만 재시작한다.** 인자 없이 `up -d` 하면 MySQL까지 재시작된다
- [ ] 이미지 태그에 커밋 SHA 사용 (`latest`만 쓰면 롤백이 불가능하다)
- [ ] 롤백 절차를 README에 기록

**완료 조건**
> 사소한 변경을 main에 머지하면, 3분 내에 `https://api.<도메인>` 에 반영된다.

**예상:** 1일

---

### Phase 6 — frontend CI

**목표:** Vercel이 잡지 못하는 것만 얇게 보완한다.

Vercel이 이미 PR마다 클린 환경 빌드 + preview 배포를 제공하므로 **우선순위가 낮다.** backend가 안정된 뒤에 진행한다.

**작업**
- [ ] `tsc --noEmit` (타입체크)
- [ ] `eslint`
- [ ] 브랜치 보호에 status check 연결

**예상:** 0.5일

---

## 4. GitHub Secrets 목록

`Settings → Secrets and variables → Actions`

두 레포가 공유하는 값은 **Organization Secrets**에 한 번만 등록한다.

| 이름 | 용도 | 등록 시점 |
|---|---|---|
| `EC2_HOST` | EC2 퍼블릭 IP 또는 도메인 | Phase 5 |
| `EC2_USER` | SSH 사용자 (`ubuntu`) | Phase 5 |
| `EC2_SSH_KEY` | 키페어 개인키 전문 | Phase 5 |
| `DB_PASSWORD` | MySQL 비밀번호 | Phase 5 |
| `JWT_SECRET` | 토큰 서명 키 (해당 시) | Phase 5 |

> Secrets는 절대 코드에 넣지 않는다. 워크플로우에서는 `${{ secrets.NAME }}` 으로 이름만 참조하며, 실행 로그에서는 자동으로 `***` 로 마스킹된다.

---

## 5. 알려진 함정 모음

작업 중 막히면 여기부터 확인한다.

| 증상 | 원인 | 해결 |
|---|---|---|
| CI에서 `./gradlew: Permission denied` | 실행 권한 없이 커밋됨 | `git update-index --chmod=+x gradlew` |
| 로컬은 되는데 CI/서버에서 클래스를 못 찾음 | macOS는 파일명 대소문자를 구분하지 않지만 리눅스는 구분함 | 파일명·import 대소문자 일치 확인 |
| 브라우저 콘솔에 Mixed Content 차단 | HTTPS 페이지에서 HTTP API 호출 | 백엔드에 도메인 + HTTPS 적용 |
| CORS 에러 | 백엔드가 Vercel 도메인을 허용하지 않음 | `allowedOrigins`에 Vercel 도메인 추가 |
| EC2에서 앱이 갑자기 죽음 | `t3.micro` 1GB 메모리 부족(OOM) | 인스턴스 상향 / swap 추가 / DB 분리 |
| 배포할 때마다 DB 데이터가 초기화됨 | volume 미설정 + MySQL까지 재시작 | volume 설정 후 `up -d app` 으로 앱만 교체 |
| 브랜치 보호에서 CI가 목록에 없음 | 해당 job이 한 번도 실행된 적 없음 | PR을 한 번 돌린 뒤 다시 설정 |
| 스케줄 워크플로우가 엉뚱한 시간에 실행 | cron이 UTC 기준 | KST = UTC + 9 (KST 03시 → `0 18 * * *`) |

---

## 6. Jira 티켓 분할 제안

각 Phase를 그대로 티켓으로 만든다. 티켓 하나는 **1~3일 내 완료 + PR 하나로 종료**되는 크기가 적정하다.

| 티켓 | Phase | 담당 제안 | AWS |
|---|---|---|---|
| 백엔드 프로젝트 초기 세팅 | 0 | BE 1명 | — |
| CI 파이프라인 구축 | 1 | BE 1명 | — |
| main 브랜치 보호 규칙 적용 | 2 | 레포 관리자 | — |
| 애플리케이션 Docker화 | 3 | BE 1명 | — |
| EC2 배포 환경 구성 및 수동 배포 | 4 | BE 1명 | ✅ |
| CD 파이프라인 구축 | 5 | Phase 4 담당자와 동일인 | ✅ |
| frontend CI 구축 | 6 | FE | — |

**Phase 4와 5는 같은 사람이 맡는다.** 4에서 얻은 서버 환경 지식이 5에 그대로 쓰이므로, 나누면 인수인계 비용이 더 크다.

Phase 0~3은 AWS와 무관하므로 **지금 즉시 착수 가능**하다.

---

## 7. 다음 액션

1. **PR 을 열어 CI 가 실제로 도는지 확인** — 초록불 확인
2. **일부러 테스트를 깨뜨린 커밋으로 ❌ 확인** — 이걸 해야 CI 를 믿을 수 있다
3. **Phase 2 브랜치 보호 규칙** — 1번으로 `빌드 및 테스트` 잡이 목록에 나타난 뒤 설정
4. **팀원에게 DB 위치 의견 요청** — A안(docker compose) vs B안(RDS)
5. **운영진에게 AWS 확인** — EC2 생성 권한 / Access Key 발급 여부 / 인스턴스 타입 제한 / RDS 권한

4~5의 답변을 기다리는 동안 1~3 은 그대로 진행할 수 있다.
