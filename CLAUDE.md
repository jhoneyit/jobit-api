# jobit (API 서버)

JD(채용공고) 기반 기술 면접 준비 + 이력서 첨삭 서비스의 **백엔드**.

제품 스펙은 `docs/jd-interview-prep-spec.md`가 원본이다. **제품 판단이 필요하면 먼저 읽는다.**

## 두 레포 구성 (중요)

이 제품은 레포 두 개로 나뉜다. 나란히 체크아웃해 두고 쓴다.

```
프로젝트/
  jobit/        ← 여기. Spring Boot REST API + LLM + DB
  jobit-front/  Next.js. UI/SSR 전용, 이 서버를 fetch 로 호출
```

- **경계**: 도메인 로직·LLM 호출·영속성은 전부 이쪽이다. `jobit-front`는 화면을 그린다.
- **인증은 프론트(Auth.js)에 남긴다.** 이 서버는 자격증명을 보지 않고, 호출 시 넘어오는
  `owner_key`로 소유자를 식별할 뿐이다. 그래서 **이 서버의 `member` 테이블에는 행이 생기지 않는다**
  — 회원은 프론트의 `user` 테이블에 있다. `member` 패키지는 현재 미사용이다.
- **API 계약의 원본은 `docs/api.md`**다. 엔드포인트를 바꾸면 여기부터 고치고 프론트를 맞춘다.
- **2026-08-04 이관 완료: DB 가 하나다.** `jobit-front` 는 이 서버의 Postgres(`:5432`)를 함께
  쓰고, JD 파싱과 질문 생성을 이 서버에 위임한다. 그쪽에 남은 것은 화면·인증·세션뿐이다.
  **스키마 소유권은 이쪽 Flyway 단일이다** — 그쪽 `drizzle-kit` 은 마이그레이션에서 손을 뗐다.
  컬럼을 바꾸면 여기 마이그레이션을 추가하고 그쪽 `src/lib/db/schema.ts` 를 맞춘다.

> **`owner_key`에는 HMAC 서명이 붙는다** (2026-08-07, `common.ServiceAuth`). 프론트와 같은
> 비밀키(`jobit.auth.service-secret` / `JOBIT_SERVICE_SECRET`)로 서명하며, `/api/*` 필터가
> 검증한다. **비밀키가 없으면 인증이 꺼진다** — 로컬 전용이고, `prod` 프로파일에서는 앱이
> 뜨지 않는다.

## 핵심 구조 (한 문단)

JD를 파싱해 `requirement` 목록을 뽑고, 이 하나의 자산을 **질문 생성**과 **이력서 갭 분석** 양쪽에서
재사용한다. `requirement`가 두 기능의 공통 앵커라는 점이 설계의 중심이다 — 질문도 요구사항에서
파생되고, 갭 판정도 요구사항 기준으로 내려진다.

```
JD 텍스트 → [파싱] → requirement[] ─┬→ question (질문·꼬리질문·답변뼈대)
                                   └→ gap_item (MET/WEAK/MISSING) → rewrite_suggestion
```

## 스택

- Java 21 (Gradle toolchain), Spring Boot 4.1.0, Gradle wrapper
- Spring MVC, Spring Data JPA, Bean Validation
- PostgreSQL + Flyway, `pgvector` 확장 (임베딩 기반 후보 추림에 사용)
- `spring-security-crypto` (BCrypt strength 12) — 해싱만. `starter-security`는 넣지 않았다
- Lombok, DevTools
- LLM: Anthropic Java SDK (`com.anthropic:anthropic-java`), 구조화 출력(JSON schema) 필수.
  Boot의 BOM이 관리하지 않으므로 `build.gradle`에 버전을 직접 박는다

### 스택 결정 (중요)

스펙 §2의 기준안은 **Next.js 단일 레포**였고, 2026-08-02에는 **Spring Boot 단일 + Thymeleaf +
htmx**로 정했었다. 둘 다 현재 결정이 아니다.

**2026-08-03 확정: Spring = REST API 서버 / Next(`jobit-front`) = 프론트.**
따라서 **Thymeleaf·htmx는 도입하지 않는다.** 응답은 HTML 조각이 아니라 JSON이고,
질문 생성 스트리밍은 SSE(`text/event-stream`)를 브라우저의 `EventSource`가 직접 받는다.

**§3 데이터 모델과 §4 핵심 흐름은 스택과 무관하게 그대로 적용된다** (스펙 명시).

## 명령어

```bash
./gradlew build          # 컴파일 + 테스트
./gradlew test           # 테스트만
./gradlew bootRun        # 앱 실행 (Docker 실행 중이어야 함 — 아래 참고)
./gradlew test --tests '*ClassName*'   # 단일 테스트
```

`gradle.properties`가 데몬 JVM 인자를 고정하고 configuration cache·빌드 캐시를 켠다. 이게 없으면
호출 주체(터미널·IDE)마다 인자가 달라져 데몬 재사용에 실패하고, 할 일이 없는 빌드에도
데몬을 새로 띄우느라 몇 초를 쓴다 (5s → 0.3s).

### 로컬 DB

`spring-boot-docker-compose`가 `compose.yaml`을 감지해 Postgres 컨테이너를 자동 기동하고
DataSource를 연결한다. **`application.properties`에 접속 정보를 쓰지 않는다** — 수동 설정을
추가하면 자동 연결과 충돌한다.

- Docker Desktop이 떠 있어야 `bootRun`이 동작한다.
- 이미지는 `pgvector/pgvector:pg17` (기본 postgres 이미지 아님). `vector` 확장이 필요하다.
- 컨테이너 데이터는 `jobit-pgdata` 볼륨에 유지된다. 초기화하려면 `docker compose down -v`.
- **앱을 내려도 컨테이너는 살려 둔다** (`spring.docker.compose.lifecycle-management=start-only`).
  기본값은 `bootRun` 종료 시 컨테이너까지 내리는데, 그러면 재시작마다 healthcheck를 다시
  기다려 부팅이 8.5초 → 2.6초 차이로 벌어진다. 이건 접속 정보가 아니라 수명 설정이라
  위의 "접속 정보를 쓰지 않는다"와 충돌하지 않는다.

> **컨테이너는 `jobit-postgres-1`(5432) 하나뿐이다.** 2026-08-04 DB 단일화 전에는
> `jobit-pg`(55432)에 `jobit-front`의 DB가 따로 있었지만, 이제 프론트도 이 컨테이너를 쓴다.
> Auth.js의 `user`/`session` 테이블도 여기 있다 (Flyway V6).

`bootRun`은 앱을 붙들고 있는 태스크라 **`BUILD SUCCESSFUL`을 찍지 않는다.** `80% EXECUTING`에서
멈춘 것처럼 보여도 로그에 `Started JobitApplication in ...`이 나왔으면 이미 뜬 것이다.
코드만 고쳤다면 재시작하지 말고 **다른 터미널에서 `./gradlew classes`** — DevTools가 1~2초 만에
부분 재시작한다. 전체 재시작은 프로퍼티·의존성을 바꿨을 때만 필요하다.

> **이 자동 연결은 테스트에는 적용되지 않는다.** `spring-boot-docker-compose`가
> `developmentOnly` 스코프라 테스트 classpath에 없다. 그래서 DB가 필요한 테스트는 접속 정보를
> 스스로 마련해야 한다 (아래 "현재 상태" 참고).

## 구조

```
src/main/java/com/jobit/
  jd/          JD 파싱, requirement 추출
  question/    질문 생성
  resume/      이력서 업로드, bullet 분해
  gap/         갭 분석, 리라이트 제안
  submission/  JD 입력 이력 (owner_key 소유)
  member/      회원, 로컬 가입, 비밀번호 재설정 — 현재 미사용
  llm/         LLM 클라이언트, 구조화 출력, 비용 로그
  common/      OwnerKey, PasswordConfig, TimeConfig
src/main/resources/
  db/migration/  Flyway (V1~V5)
```

레이어 규칙과 패키지 경계 근거는 `docs/architecture.md`에 있다.

### owner_key

개인 자산의 소유자 식별자. 규약은 `common.OwnerKey`가 강제한다 — **접두사가 규약의 전부다.**

```
로그인   user:<user_id>    ← jobit-front 의 user 테이블 ID
비로그인 anon:<세션 쿠키>
```

회원 ID와 익명 세션 키가 한 컬럼을 공유하므로 접두사가 없으면 두 네임스페이스가 충돌한다.
새로 만드는 조회 경로는 `owner_key` 하나만 보고, 로그인 여부로 분기하지 않는다.

### 비밀값

키 목록은 `.env.example`, 실제 값은 `.env`(`.gitignore` 대상)에 넣는다.

**`.env` 는 `application.properties` 의 `spring.config.import` 가 읽는다.** Spring Boot 가
자동으로 읽어 주지 않으므로 그 줄이 없으면 파일을 만들어 둬도 조용히 무시된다 — 호출자 인증이
꺼진 채로 뜨는 것이 그 결과였다. OS 환경변수가 `.env` 보다 우선한다.

**`ANTHROPIC_API_KEY`는 OS 환경변수로 넣는다.** Anthropic Java SDK의
`AnthropicOkHttpClient.fromEnv()`는 OS 환경변수만 읽는다 —
`application-local.properties`나 `spring.config.import`로 로드한 값은 Spring Environment에만
올라가므로 `fromEnv()`가 찾지 못한다. Spring 프로퍼티로 관리하려면 `@Value`로 주입해
`AnthropicOkHttpClient.builder().apiKey(...)`를 직접 호출해야 한다.

## 문서

- `docs/jd-interview-prep-spec.md` — **제품 스펙 원본**. 데이터 모델(§3), 핵심 흐름(§4), 로드맵(§5)
- `docs/architecture.md` — Spring 구현 매핑, 레이어 규칙, 두 레포 경계, 결정 기록
- `docs/api.md` — **엔드포인트 명세 = 프론트와의 계약 원본**

## 작업 원칙

스펙에서 도출된, 구현 시 반드시 지킬 것들.

- **MISSING 항목은 절대 지어내지 않는다.** 근거 없는 요구사항에 대해 이력서 문장을 창작하는 코드나
  프롬프트를 작성하지 말 것. "근거 없음"을 그대로 노출하고 질문 생성으로 넘긴다. (스펙 §4.5)
- **리라이트는 문장 단위.** 이력서 전체를 LLM에 보내지 않는다. `resume_bullet` 하나씩 처리한다.
- **갭 분석은 2단계.** 임베딩으로 후보 3개 추림 → LLM은 판정만. 요구사항×문장 전수 LLM 호출 금지.
- **캐시 먼저.** JD는 `content_hash`, 갭 분석은 `(resume_id, job_posting_id)` 유니크로 재사용.
- **`llm_call_log`는 처음부터.** LLM 호출 경로를 새로 만들면 비용 로깅을 함께 넣는다.
- **개인정보.** 이력서 원문은 암호화 저장 + `expires_at` TTL. 로그에 원문을 남기지 않는다.
- **스키마 변경은 Flyway로.** `ddl-auto` 사용 금지. 적용된 마이그레이션은 수정하지 말고 새 버전 추가.

## 현재 상태

**로드맵 1단계 완료 — JD 파싱과 질문 생성이 끝까지 동작하고, 프론트가 이 서버를 호출한다.**

있는 것:

- **`POST /api/jd/parse` 동작** (docs/api.md). 정규화 → 캐시 → LLM 파싱 → 저장까지 전 경로
- **`GET /api/questions` 동작** — SSE 스트리밍. 완성된 질문을 하나씩 흘려보낸다.
  `IncrementalArrayParser` 가 스트림에서 완성된 배열 원소를 골라낸다 (문자열 안의 중괄호·
  이스케이프에 속지 않는 상태 기계). `prompt_version` 이 같으면 재생성하지 않는다.
  실측: in=2,813 out=3,526 $0.102 / 62초, 두 번째 호출은 캐시로 0초
- **`GET·DELETE /api/submissions` + `POST /api/submissions/claim` 동작** — 내 기록 목록·삭제·
  익명 승계. 목록 한 줄에 붙는 집계 셋(요구사항 수·질문 수·갭 요약)을 **공고 ID를 한 번에 넘겨**
  가져온다. 이걸로 프론트가 `jd_submission`을 직접 읽던 마지막 경로가 사라졌다
  (관리자 콘솔은 여전히 DB를 직접 읽는다 — 운영용이라 대응 엔드포인트가 없다)
- **LLM 연동** — Anthropic Java SDK, 구조화 출력 + 서버 재검증 + 3회 재시도, `llm_call_log` 비용 기록
- 엔티티 + 리포지토리 전체 (`jd` / `question` / `resume` / `gap` / `submission` / `member` / `llm`)
- Flyway V1~V10 — pgvector 확장, 코어 스키마, 로컬 가입 컬럼, `password_reset_token`,
  `owner_key` 전환, DB 단일화, 레이트 리밋, 프로필, 면접 연습(V9~V10)
- 서비스: `JdParsingService`(캐시·경합 처리),
  `JdSubmissionService`(이력 목록 + 갭 요약, N+1 회피, 익명→계정 승계)
- 미사용 서비스: `LocalAccountService`, `PasswordResetService`, `MemberService` — 인증이 프론트에 있다
- **면접 연습 완료** (`docs/interview-practice-design.md`) — 백엔드 6종 + 프론트 화면
  (`/interview`, `/profile/interviews`) + `transcript` TTL 정리까지. 스펙에 없는 새 축이다.
  채점 기준은 새로 만들지 않고 `question.answer_outline`을 쓴다 — 사용자가 결과 화면에서 본
  그 뼈대가 그대로 기준이다. **오디오는 저장하지 않는다**: STT는 브라우저(Web Speech API)
  몫이고 서버는 텍스트만 받는다. 실측: 채점 1회 in=2,246 out=174 $0.0156 / 6.6초,
  5문항 세션 $0.078
- 테스트 177개 전부 통과. `contextLoads()`가 Testcontainers로 실제 Postgres를 띄워
  **Flyway 마이그레이션·엔티티 매핑·JPQL을 매번 검증한다.**
  매핑 검증은 `spring.jpa.hibernate.ddl-auto=validate` 덕분이다 — 이 줄이 없으면 기본값이
  `none`이라(Testcontainers Postgres는 임베디드가 아니다) **컬럼명을 틀려도 컨텍스트는 뜬다.**
  2026-08-07까지 실제로 그 상태였다. 저장·조회까지 보려면 별도 테스트가 필요하다
  (`InterviewPersistenceTest`)

없는 것 (= 다음 작업 후보):

- 제출 이력 조회·삭제 엔드포인트 — `JdSubmissionService` 는 있고 컨트롤러가 없다.
  그동안 프론트가 DB 를 직접 읽고 있다
- 갭 분석·리라이트 (3~4단계)
- pgvector Hibernate 타입 매핑 — `resume_bullet.embedding`은 JPA 표준 타입이 아니다
- 인증 관련 컨트롤러 — 인증이 프론트에 있으므로 당분간 필요 없다

### 지출 방어 (LlmGuard)

두 겹이다. 설정은 `application.properties` 의 `jobit.llm.*`.

| | 무엇을 막나 | 기본값 |
|---|---|---|
| 소유자별 시간당 한도 | 한 사람의 폭주 | 20회 |
| 전역 일일 비용 상한 | 여러 사람이 몰렸을 때의 총액 | $5.00 |
| 면접 연습 일별 세션 | 세션 1건 = LLM N회인 유일한 기능 | 6세션 |

**면접 연습만 세 번째 겹이 필요하다.** 다른 기능은 요청 1건 = LLM 1회지만 면접 연습은 세션 1건이
문항 수만큼을 먹어서, 시간당 한도만으로는 세션 네 번에 소진되며 공고 분석까지 함께 막힌다.
`calls-per-hour` 를 올려 해결하지 않는다 — 올리면 다른 기능 방어까지 헐거워진다.
카운터는 `interview_session` 을 직접 센다 (`rate_limit_bucket` 은 창을 2시간 뒤 정리해서
일별 창을 둘 수 없다).

- **횟수만으로는 부족하다.** 기능마다 단가가 열 배씩 차이 난다 (파싱 $0.034 / 질문 생성 $0.102).
  그래서 총액은 `llm_call_log.cost_usd` 합계로 따로 막는다.
- **캐시 적중은 소비하지 않는다.** 그래서 인터셉터가 아니라 **돈이 나가기 직전 한 지점**에서
  부른다 — 지금 그 지점은 `JdParsingService` 와 `QuestionService` 두 곳이다.
- **카운터는 DB에 있다** (`rate_limit_bucket`). 메모리로 두면 재시작에 초기화되고 인스턴스를
  늘리면 각자 따로 센다. 증가는 `on conflict do update ... returning` 으로 원자적이다 —
  조회 후 증가로 나누면 동시 요청이 같은 값을 읽어 한도를 넘긴다.
- **고정 창이라 경계에서 최대 두 배가 통과할 수 있다** (12:59에 20회 + 13:00에 20회).
  비용 방어가 목적이라 허용하고, 총액은 일일 상한이 막는다.
- SSE 는 이미 200 으로 헤더가 나간 뒤라 `@ExceptionHandler` 가 끼어들 수 없다.
  질문 생성 쪽은 상태 코드 대신 `error` 이벤트로 알린다.

### LLM 연동에서 알아둘 것

- **API 키가 없으면 `AnthropicJdParser` 빈이 등록되지 않고** `JdParserFallbackConfig`의 폴백이
  자리를 지킨다. 앱은 뜨고, 파싱을 호출하면 명확한 예외가 난다 (캐시 적중은 정상 동작).
  이 갈림을 `JdParserWiringTest`가 양쪽 다 고정한다.
- **`outputConfig(Class)`가 effort를 조용히 지운다.** SDK가 클래스에서 스키마를 파생시키며
  `OutputConfig`를 통째로 새로 만들기 때문이다. `StructuredOutput.withEffort`로 다시 조립해야
  하고, 이걸 놓치면 파싱이 기본 effort(high)로 돌아 비용이 몇 배가 된다.
  `JdParseParamsTest`가 막고 있다 — SDK를 올릴 때 이 테스트가 깨지면 거기부터 본다.
- **`thinking`을 끄지 않는다.** Opus 5에서 끄면 도구 호출이 일반 텍스트로 새거나
  `<thinking>` 태그가 응답에 섞이는 실패 모드가 있다. 비용은 effort로 낮춘다.
- **재시도도 돈이 나가므로 시도마다 `llm_call_log`에 기록한다.**
- **실제 호출 검증은 `AnthropicJdParserSmokeTest`에서 한다.** 나머지 테스트는 요청 조립과 빈
  배선만 보므로, 그 요청이 실제로 통하는지(인증·스키마 파생·effort/thinking 조합·역직렬화)는
  아무도 확인하지 않는다. Docker도 Spring 컨텍스트도 타지 않고 LLM 경로만 태운다.

  ```bash
  export ANTHROPIC_API_KEY=sk-ant-...
  JOBIT_LLM_SMOKE=1 ./gradlew test --tests '*AnthropicJdParserSmokeTest*' -i
  ```

  **`JOBIT_LLM_SMOKE`가 없으면 건너뛴다**(실패가 아니다). 키만 있다고 매 빌드마다 과금되면
  안 되므로 켜는 스위치를 따로 뒀다. SDK를 올리거나 프롬프트·스키마를 고친 뒤에는 이걸 한 번 돌린다.

### 알려진 문제

- **비밀키 하나가 전부다.** `jobit.auth.service-secret`이 새면 모든 소유자를 사칭할 수 있다.
  비대칭 키나 mTLS로 좁힐 수 있지만 과하다고 봤다. **키 회전 절차가 아직 없다** — 지금은
  양쪽을 동시에 바꿔야 해서 무중단 회전이 안 된다.
- **비밀키를 설정하지 않으면 인증이 꺼진 채로 뜬다.** 부팅 로그의 경고를 보고 알아채야 한다.
- **테스트에 Docker가 필요하다.** `contextLoads()`가 Testcontainers로 Postgres를 띄운다.
  Docker가 없으면 이 테스트만 실패한다.

## 주의

**Boot 4 기준.** 의존성 좌표가 Boot 3과 다르다 (`spring-boot-starter-web` → `spring-boot-starter-webmvc`).
Boot 3 문서를 그대로 따르지 말 것.
