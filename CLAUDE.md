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
- LLM: **로컬 Ollama + Qwen3** (2026-08-10, Anthropic 에서 전환). SDK 없이 `RestClient` 로
  `/api/chat`·`/api/embed` 를 직접 부른다 (`llm/OllamaChatClient`, `llm/OllamaEmbeddingClient`).
  구조화 출력은 Ollama `format` 에 JSON Schema 를 실어 강제하고, 스키마는 `llm/JsonSchemas` 가
  응답 record 에서 파생시킨다 (SDK 의 `outputConfig(Class)` 가 하던 일)
- **Jackson 은 Boot 4 기준 Jackson 3 (`tools.jackson`)이다.** Boot 4 의 `starter-webmvc` 는
  Jackson 을 딸려 오지 않아 `starter-json` 을 직접 선언했다 — 예전에 클래스패스에 있었던 건
  anthropic-java 의 전이 의존(Jackson 2)이었다. 애너테이션(`@JsonProperty` 등)만
  `com.fasterxml.jackson.annotation` 그대로다

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

**LLM 스위치는 `.env` 의 `ollama.base-url` 이다** — API 키가 아니라 주소이고, 로컬 Ollama 라
인증이 없다. 이 파일에서 이것만 SHOUT_CASE 가 아니다.
`@ConditionalOnProperty("ollama.base-url")` 가 그 이름을 그대로 찾는데,
`OLLAMA_BASE_URL` → `ollama.base-url` 완화 바인딩은 **OS 환경변수 소스에만** 적용되고
properties 로 읽는 `.env` 에는 적용되지 않는다. SHOUT_CASE 로 적으면 조용히 무시되고 폴백이
자리를 지킨다.

**`application.properties` 에 `ollama.base-url=${OLLAMA_BASE_URL:http://localhost:11434}` 같은
기본값 다리를 놓지 않는다.** `@ConditionalOnProperty` 는 값이 있기만 하면 매칭하므로, 그 줄을
넣으면 프로퍼티가 항상 "있음"이 되어 폴백 4개(JdParser·ResumeParser·AnswerScorer·
EmbeddingClient)가 영영 물리지 않는다 — "LLM 설정 없이도 앱은 뜬다"가 조용히 사라진다.
(`jobit.auth.service-secret` 과 `jobit.resume.encryption-key` 는 `@Value` 로 받아 직접
빈 값을 검사하므로 다리를 놓아도 된다 — 조건이 붙은 쪽만 문제다.)

**`JOBIT_RESUME_KEY` 는 SHOUT_CASE 다** (`application.properties` 가 다리를 놓는다).
없으면 이력서 업로드가 거부되고, `prod` 에서는 앱이 뜨지 않는다.

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
  두 번째 호출은 캐시로 0초 (Anthropic 시절 실측 in=2,813 out=3,526 / 62초 — Ollama 전환 후
  로컬 재실측 필요)
- **`GET·DELETE /api/submissions` + `POST /api/submissions/claim` 동작** — 내 기록 목록·삭제·
  익명 승계. 목록 한 줄에 붙는 집계 셋(요구사항 수·질문 수·갭 요약)을 **공고 ID를 한 번에 넘겨**
  가져온다. 이걸로 프론트가 `jd_submission`을 직접 읽던 마지막 경로가 사라졌다
  (관리자 콘솔은 여전히 DB를 직접 읽는다 — 운영용이라 대응 엔드포인트가 없다)
- **LLM 연동** — 로컬 Ollama + Qwen3 (`RestClient` 직접 호출, SDK 없음), 구조화 출력
  (`JsonSchemas` 파생 → `format`) + 서버 재검증 + 3회 재시도, `llm_call_log` 기록
  (비용은 0, 토큰·지연 관측용). **2026-08-10 Anthropic 에서 전환** — 외부로 나가는 호출이
  하나도 없어졌고, 임베딩까지 제공자가 하나로 합쳐졌다
- 엔티티 + 리포지토리 전체 (`jd` / `question` / `resume` / `gap` / `submission` / `member` / `llm`)
- Flyway V1~V11 — pgvector 확장, 코어 스키마, 로컬 가입 컬럼, `password_reset_token`,
  `owner_key` 전환, DB 단일화, 레이트 리밋, 프로필, 면접 연습(V9~V10),
  임베딩 1024차원 전환(V11 — **기존 벡터는 버려졌다.** 컬럼을 drop+add 했으므로 V11 이전에
  올린 이력서는 재업로드해야 갭 분석에 쓰인다)
- 서비스: `JdParsingService`(캐시·경합 처리),
  `JdSubmissionService`(이력 목록 + 갭 요약, N+1 회피, 익명→계정 승계)
- 미사용 서비스: `LocalAccountService`, `PasswordResetService`, `MemberService` — 인증이 프론트에 있다
- **면접 연습 완료** (`docs/interview-practice-design.md`) — 백엔드 6종 + 프론트 화면
  (`/interview`, `/profile/interviews`) + `transcript` TTL 정리까지. 스펙에 없는 새 축이다.
  채점 기준은 새로 만들지 않고 `question.answer_outline`을 쓴다 — 사용자가 결과 화면에서 본
  그 뼈대가 그대로 기준이다. **오디오는 저장하지 않는다**: STT는 브라우저(Web Speech API)
  몫이고 서버는 텍스트만 받는다. (Anthropic 시절 걸었던 `cache_control` 프롬프트 캐싱은
  Ollama 전환으로 사라졌다 — 해당 API 가 없고, 같은 프리픽스는 KV 캐시로 자동 재사용된다.
  대신 `OLLAMA_KEEP_ALIVE` 를 넉넉히 잡아야 세션 중간에 모델이 내려가지 않는다)
- **이력서 업로드 완료** (2026-08-09, docs/api.md "이력서") — 텍스트 업로드 → LLM 문장 분해 →
  임베딩 → 저장까지 한 경로. 엔드포인트 5종. **여기서 pgvector가 처음 실제로 쓰인다.**
  - **원문은 AES-256-GCM 으로 암호화 저장한다** (`common.TextCipher`). 키가 없으면 평문으로
    폴백하지 않고 **업로드를 거부한다** — 인증과 반대 결정이고, 평문 이력서는 되돌릴 수 없어서다
  - **임베딩도 같은 Ollama 다** (`qwen3-embedding:0.6b`, 1024차원 — V11 에서 컬럼을 맞췄다).
    처음에는 OpenAI `text-embedding-3-small`(1536차원)이었는데, Anthropic 에 임베딩 API 가
    없어서였다. Ollama 는 생성과 임베딩을 한 서버에서 주므로 두 번째 제공자가 사라졌다.
    **모델을 바꾸면 차원부터 본다** — 다르면 Flyway 마이그레이션 + 재업로드가 따라온다
  - **LLM 호출을 트랜잭션 밖에 뒀다.** `JdParsingService`와 다른데, 분해가 수십 초라 그동안
    커넥션을 붙들면 동시 업로드 몇 건에 풀이 마르고 무관한 기능까지 멈춘다. 임베딩까지 끝낸 뒤
    `TransactionTemplate`으로 짧은 쓰기 트랜잭션만 연다
  - **문장 순서와 벡터 순서가 어긋나면 저장하지 않는다.** 밀린 인덱스는 결과가 그럴듯해서
    사후에 가장 찾기 어렵다. (OpenAI 시절에는 응답의 `index` 로 재정렬까지 했지만 Ollama
    `/api/embed` 응답에는 index 가 없다 — 배열 위치가 곧 순서라, 남은 방어는 개수 일치뿐이다)
  - 90일 TTL + `ResumeCleanup`. 면접 답변과 달리 **행을 통째로 지운다** — 이력서는 문장 자체가
    내용의 전부라 원문을 지우고 남길 것이 없다
- **갭 분석 완료** (2026-08-14, docs/api.md "갭 분석") — 스펙 §4.3 의 2단계 구조 그대로.
  임베딩이 요구사항마다 이력서 문장 후보 3개를 추리고(`findNearest`), LLM 은 판정만 한다
  (`gap/OllamaGapJudge`, 요구사항 1개당 호출 1회). `POST·GET /api/gap-analyses` 2종,
  제출 이력의 `gapSummary` 도 이때부터 채워진다.
  - **근거를 대지 못한 MET/WEAK 는 MISSING 으로 내린다** (`GapVerdictNormalizer`).
    지어낸 충족이 이 제품이 가장 금지하는 실수라, 틀릴 거면 사용자가 준비를 더 하게 만드는
    쪽으로 틀린다. 근거 없는 충족 판정만 한 번 재시도한다 — 강등이 거짓 MISSING 이 되는
    경우를 줄이기 위해서다
  - **임베딩 0개 이력서는 한도 소비 전에 409 로 끊는다** — V11 이전 업로드가 실존하고,
    통과시키면 전부 MISSING 이 캐시에 굳는다
  - **GET 은 분석을 시작하지 않는다.** 재방문 화면이 몇 분짜리 LLM 경로를 태우면 안 된다.
    시작은 언제나 명시적인 POST 다
  - 캐시 키는 `(resume, jobPosting)` 유니크 그대로. 이력서 수정 = 새 업로드 = 새 resumeId 라
    캐시 무효화 문제가 애초에 없다. 경합은 유니크 제약 + 재조회로 처리한다
- 테스트 289개 전부 통과 (스모크 4개는 스위치가 없어 건너뜀).
  `ResumeEmbeddingPersistenceTest`가 **pgvector 경로를 실제 Postgres로 검증한다** —
  `::vector` 캐스트·`<=>` 연산자·리터럴 형식·차원 수는 넷 다 컴파일러가 봐주지 않고,
  셋은 예외조차 없이 그냥 틀린 순서를 돌려준다.
  `contextLoads()`가 Testcontainers로 실제 Postgres를 띄워
  **Flyway 마이그레이션·엔티티 매핑·JPQL을 매번 검증한다.**
  매핑 검증은 `spring.jpa.hibernate.ddl-auto=validate` 덕분이다 — 이 줄이 없으면 기본값이
  `none`이라(Testcontainers Postgres는 임베디드가 아니다) **컬럼명을 틀려도 컨텍스트는 뜬다.**
  2026-08-07까지 실제로 그 상태였다. 저장·조회까지 보려면 별도 테스트가 필요하다
  (`InterviewPersistenceTest`)

없는 것 (= 다음 작업 후보):

- **리라이트 (4단계) — 선행 조건이 갖춰졌다.** 갭 분석의 WEAK 항목이 입력이다.
  **이력서 원문 복호화 경로가 열리는 유일한 지점**이기도 하다 (`TextCipher.decrypt` 는
  아직 테스트 외에 호출부가 없다)
- 이력서·갭 분석 화면 — 백엔드만 있고 `jobit-front` 에 대응 화면이 없다
- 인증 관련 컨트롤러 — 인증이 프론트에 있으므로 당분간 필요 없다

> **pgvector Hibernate 타입 매핑은 하지 않기로 했다.** 이 컬럼의 실제 사용처가 코사인 유사도
> 상위 N개 조회 하나뿐이라, 엔티티에 매핑해 봐야 1024개짜리 배열을 메모리로 실어 나르는 일만
> 생기고 정작 필요한 `<=>` 는 JPQL로 표현되지 않아 어차피 네이티브 쿼리가 된다.
> `ResumeBullet` 은 이 컬럼을 모른 채로 두고 `ResumeBulletEmbeddingRepository` 만 다룬다.

### 자원 방어 (LlmGuard)

**막는 대상이 돈에서 시간으로 바뀌었다** (2026-08-10 Ollama 전환). 로컬 추론에는 토큰 요금이
없다. 대신 GPU 가 하나뿐이라 폭주하는 요청 하나가 다른 모든 요청을 줄 세운다 — 피해가
청구서에서 대기 시간으로 바뀌었을 뿐 방어는 여전히 필요하다. 설정은 `jobit.llm.*`.

| | 무엇을 막나 | 기본값 |
|---|---|---|
| 소유자별 시간당 한도 | 한 사람의 폭주 | 20회 |
| 전역 일일 비용 상한 | (휴면) 유료 제공자 복귀 시 총액 | **0 = 꺼짐** |
| 면접 연습 일별 세션 | 세션 1건 = LLM N회인 유일한 기능 | 6세션 |

**일일 비용 상한은 꺼져 있다.** `LlmPricing` 이 로컬 모델을 전부 0 원으로 계산해 어차피 발동하지
않는다 — 스위치(`daily-budget-usd=0`)와 계산이 둘 다 0 을 가리키게 맞춰 뒀다. 유료 제공자를
다시 붙이면 `LlmPricing` 의 단가와 함께 되살린다. **모르는 모델을 0 원으로 잡는 것도 그때
뒤집어야 한다** — 지금은 과대 계상(지어낸 금액이 상한에 쌓여 멀쩡한 요청을 막는 것)이 위험이라
0 이 기본이지만, 유료로 돌아가면 과소 계상이 위험이다.

**면접 연습만 세 번째 겹이 필요하다.** 다른 기능은 요청 1건 = LLM 1회지만 면접 연습은 세션 1건이
문항 수만큼을 먹어서, 시간당 한도만으로는 세션 네 번에 소진되며 공고 분석까지 함께 막힌다.
`calls-per-hour` 를 올려 해결하지 않는다 — 올리면 다른 기능 방어까지 헐거워진다.
카운터는 `interview_session` 을 직접 센다 (`rate_limit_bucket` 은 창을 2시간 뒤 정리해서
일별 창을 둘 수 없다).

- **캐시 적중은 소비하지 않는다.** 그래서 인터셉터가 아니라 **추론이 일어나기 직전 한 지점**에서
  부른다 — 지금 그 지점은 `JdParsingService` 와 `QuestionService` 두 곳이다.
- **카운터는 DB에 있다** (`rate_limit_bucket`). 메모리로 두면 재시작에 초기화되고 인스턴스를
  늘리면 각자 따로 센다. 증가는 `on conflict do update ... returning` 으로 원자적이다 —
  조회 후 증가로 나누면 동시 요청이 같은 값을 읽어 한도를 넘긴다.
- **고정 창이라 경계에서 최대 두 배가 통과할 수 있다** (12:59에 20회 + 13:00에 20회). 허용한다.
- SSE 는 이미 200 으로 헤더가 나간 뒤라 `@ExceptionHandler` 가 끼어들 수 없다.
  질문 생성 쪽은 상태 코드 대신 `error` 이벤트로 알린다.

### 로컬 LLM (Ollama) 에서 알아둘 것

돌리려면: `ollama serve` 가 떠 있고 모델 둘을 받아 둬야 한다.

```bash
brew install ollama && ollama serve
ollama pull qwen3:14b            # 생성 (LlmModelConfig.DEFAULT_MODEL)
ollama pull qwen3-embedding:0.6b # 임베딩 — 별개로 받아야 한다
```

- **`ollama.base-url` 이 없으면 LLM 빈 4개가 등록되지 않고** 폴백이 자리를 지킨다. 앱은 뜨고,
  해당 기능을 호출하면 명확한 예외가 난다 (캐시 적중은 정상 동작). 이 갈림을
  `JdParserWiringTest`·`AnswerScorerWiringTest`·`ResumeWiringTest` 가 양쪽 다 고정한다.
- **구조화 출력 스키마는 `JsonSchemas` 가 응답 record 에서 파생시킨다.** SDK 가 하던 일이라
  이제 우리 코드고, 실수가 전부 조용하다 — 설명(`@JsonPropertyDescription`)이 빠지면 프롬프트
  절반이 사라진 채 나가고, 널 허용이 뒤집히면 모델이 없는 값을 지어낸다. `JsonSchemasTest` 가
  파생 규칙과 "모든 응답 필드에 설명이 있다", "모든 배열에 상한이 있다"를 고정한다.
- **배열에는 반드시 상한(`@MaxItems`)을 건다.** Ollama 는 `format` 을 GBNF 문법으로 바꾸므로
  상한 없는 배열은 "원소를 하나 더"가 언제나 합법이고, 모델이 반복에 빠져도 **문법이 종료를
  강제하지 못한 채** 출력 상한까지 간다. 2026-08-13 JD 파싱이 `parsed.keywords` 에 같은 문구를
  100번 넣어 4,000토큰을 태우고 `requirements` 는 시작도 못 한 채 잘렸다 (파싱 1건 5분 25초 →
  상한을 걸고 50초). 응답 record 에 적혀 있던 **"개수 제약은 구조화 출력이 지원하지 않는다"는
  Anthropic 시절의 사실이고 Ollama 에는 맞지 않는다** — `maxItems:3` 을 주면 정확히 3개가 나온다.
  정확한 개수와 하한은 여전히 문법으로 못 박을 수 없어 프롬프트와 재검증이 맡는다. 상한에 닿으면
  오류가 아니라 조용히 닫히므로 **정상 결과가 절대 닿지 않을 값 중 가장 작은 것**을 고른다.
- **위 폭주를 반복 페널티로 막으려 하지 말 것 — 재 보고 버린 선택지다.**
  `ollama show --parameters qwen3:14b` 에 `repeat_penalty` 가 1(=없음)로 보여서 "이걸 켜면 되겠다"
  싶어지는 자리다. 상한 없는 배열로 폭주를 재현시켜 측정했다 (2026-08-13):
  - **권장값은 무효.** `repeat_penalty=1.1` 도 `presence_penalty=1.5` 도 (`repeat_last_n=256` 과 함께)
    **4회 중 4회 폭주**, 페널티 없는 baseline 과 출력 토큰까지 같았다.
  - **극단값은 출력을 망가뜨린다.** `repeat_penalty=2.0` 은 키워드를 3개만 내고 "API 설게" 같은
    오타를 냈다. 자유 텍스트에서는 한국어에 중국어(`支付平台`)가 섞였고, `frequency_penalty=2.0`
    은 답을 통째로 영어로 바꿔 버렸다.
  - **`presence_penalty=2.0` 은 오히려 원소를 41개에서 63개로 늘렸다.** 당연한 결과다 — 이 계열은
    새 토큰 쪽으로 밀어주는데, 배열에서 "새 원소를 더 내라"는 것이 바로 폭주다.

  **페널티는 반복을 겨냥하지만 이 실패는 종료 실패였다.** 반복은 증상이고 원인이 아니다. 둘은
  다른 축이라 값을 어떻게 고르든 답이 되지 않고, 종료를 강제할 수 있는 것은 문법(`@MaxItems`)뿐이다.
  옵션이 무시되는 것은 아니라는 것도 확인했다 — 탐욕 디코딩(`temperature=0`, `top_k=1`)으로 고정하고
  값만 바꾸면 출력이 달라진다. 즉 **"안 듣는" 손잡이가 아니라 "다른 것을 고치는" 손잡이다.**
- **`num_ctx` 를 요청마다 명시한다** (`jobit.llm.ollama.num-ctx`, 기본 16384). Ollama 기본
  컨텍스트는 훨씬 작고, 넘치면 **에러 없이 입력 앞부분이 잘린다** — 긴 공고가 그럴듯하게 틀리는
  경로다. `LlmModelConfig` 의 maxTokens 는 이 창을 입력과 나눠 쓰므로 함께 봐야 한다.
- **thinking 은 `Effort.HIGH` 에서만 켠다** — 지금은 질문 생성·리라이트뿐이다. Anthropic 시절
  "끄면 안 된다"였던 것이 뒤집혔다: Qwen3 는 끄는 것이 공식 지원이고, 로컬에서 켜는 비용은
  돈이 아니라 사용자 대기 시간이다. 채점은 세션당 문항 수만큼 반복이라 특히 켜면 안 된다.
- **temperature=0 금지.** Qwen3 는 탐욕적 디코딩에서 같은 문장을 반복하는 실패 모드가 있다.
  구조화 출력이라고 온도를 낮추지 말 것 — `OllamaRequestBodyTest` 가 막고 있다.
- **프롬프트 캐싱 API 가 없다.** 같은 프리픽스는 KV 캐시로 자동 재사용된다. 대신 모델이
  메모리에서 내려가면 캐시도 함께 사라지므로, 면접 연습을 돌릴 때는 `OLLAMA_KEEP_ALIVE` 를
  넉넉히 (예: `1h`) 잡아 둔다.
- **재시도도 시간이 나가므로 시도마다 `llm_call_log`에 기록한다.** 비용은 0 이지만 토큰 수와
  지연이 남는다 — 어느 기능이 느린지 보는 장부다.
- **실제 호출 검증은 `OllamaJdParserSmokeTest`·`OllamaAnswerScorerSmokeTest`에서 한다.**
  나머지 테스트는 요청 조립과 빈 배선만 보므로, 그 요청이 실제로 통하는지(스키마의 GBNF 변환·
  think 조합·역직렬화)와 **이 크기의 모델이 쓸만한 결과를 내는지**는 스모크만 안다.
  모델을 더 작은 것으로 내리고 싶으면 채점 스모크(변별력 검사)부터 돌린다.

  ```bash
  JOBIT_LLM_SMOKE=1 ./gradlew test --tests '*SmokeTest*' -i
  ```

  **`JOBIT_LLM_SMOKE`가 없으면 건너뛴다**(실패가 아니다). 과금은 없지만 로컬 추론이 몇 분
  걸리므로 스위치를 유지한다. 모델·프롬프트·스키마 파생을 고친 뒤에는 이걸 한 번 돌린다.

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
