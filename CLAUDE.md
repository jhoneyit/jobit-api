# jobit

JD(채용공고) 기반 기술 면접 준비 + 이력서 첨삭 서비스. 현재 스캐폴딩 상태이며 도메인 코드는 아직 없다.

제품 스펙은 `docs/jd-interview-prep-spec.md`가 원본이다. **제품 판단이 필요하면 먼저 읽는다.**

## 핵심 구조 (한 문단)

JD를 파싱해 `requirement` 목록을 뽑고, 이 하나의 자산을 **질문 생성**과 **이력서 갭 분석** 양쪽에서
재사용한다. `requirement`가 두 기능의 공통 앵커라는 점이 설계의 중심이다 — 질문도 요구사항에서
파생되고, 갭 판정도 요구사항 기준으로 내려진다.

```
JD 텍스트 → [파싱] → requirement[] ─┬→ question (질문·꼬리질문·답변뼈대)
                                   └→ gap_item (MET/WEAK/MISSING) → rewrite_suggestion
```

## 스택

- Java 25 (Gradle toolchain), Spring Boot 4.1.0, Gradle wrapper
- Spring MVC, Spring Data JPA, Bean Validation
- PostgreSQL + Flyway, `pgvector` 확장 (임베딩 기반 후보 추림에 사용)
- Lombok, DevTools
- 화면: Thymeleaf + htmx (아직 의존성 미추가)
- LLM: API 종량제, 구조화 출력(JSON schema) 필수 (아직 의존성 미추가)

### 스택 결정 (중요)

스펙 §2의 기준안은 **Next.js 단일 레포**지만, §7 열린 결정을 **Spring Boot 단일 + Thymeleaf +
htmx**로 확정했다 (2026-08-02). 스펙 §2/§7의 Next 관련 서술은 이 프로젝트에서는 무효다.
단, **§3 데이터 모델과 §4 핵심 흐름은 스택과 무관하게 그대로 적용**된다 (스펙 명시).

스펙이 지적한 트레이드오프는 유효하다: 로드맵 4단계(리라이트 채택/되돌리기 UI)에서 htmx의
클라이언트 상태 관리가 한계에 부딪힐 수 있다. 그 시점에 재검토한다.

## 명령어

```bash
./gradlew build          # 컴파일 + 테스트
./gradlew test           # 테스트만
./gradlew bootRun        # 앱 실행 (Docker 실행 중이어야 함 — 아래 참고)
./gradlew test --tests '*ClassName*'   # 단일 테스트
```

### 로컬 DB

`spring-boot-docker-compose`가 `compose.yaml`을 감지해 Postgres 컨테이너를 자동 기동하고
DataSource를 연결한다. **`application.properties`에 접속 정보를 쓰지 않는다** — 수동 설정을
추가하면 자동 연결과 충돌한다.

- Docker Desktop이 떠 있어야 `bootRun`이 동작한다.
- 이미지는 `pgvector/pgvector:pg17` (기본 postgres 이미지 아님). `vector` 확장이 필요하다.
- 컨테이너 데이터는 `jobit-pgdata` 볼륨에 유지된다. 초기화하려면 `docker compose down -v`.

## 구조

```
src/main/java/com/jobit/
  jd/          JD 파싱, requirement 추출
  question/    질문 생성
  resume/      이력서 업로드, bullet 분해
  gap/         갭 분석, 리라이트 제안
  llm/         LLM 클라이언트, 구조화 출력, 비용 로그
src/main/resources/
  templates/   Thymeleaf + htmx
  db/migration/  Flyway (V1__*.sql)
```

### 비밀값

키 목록은 `.env.example` 참고. 실제 값은 커밋하지 않는다.

**`ANTHROPIC_API_KEY`는 OS 환경변수로 넣는다.** Anthropic Java SDK의
`AnthropicOkHttpClient.fromEnv()`는 OS 환경변수만 읽는다 —
`application-local.properties`나 `spring.config.import`로 로드한 값은 Spring Environment에만
올라가므로 `fromEnv()`가 찾지 못한다. Spring 프로퍼티로 관리하려면 `@Value`로 주입해
`AnthropicOkHttpClient.builder().apiKey(...)`를 직접 호출해야 한다.

## 문서

- `docs/jd-interview-prep-spec.md` — **제품 스펙 원본**. 데이터 모델(§3), 핵심 흐름(§4), 로드맵(§5)
- `docs/architecture.md` — Spring 구현 매핑, 레이어 규칙, 결정 기록
- `docs/api.md` — 엔드포인트 명세

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

## 현재 상태 / 블로커

로드맵 1단계(JD 붙여넣기 → 파싱 → 질문 10개 스트리밍) 시작 전이다.

- **미추가 의존성**: Thymeleaf, htmx, LLM SDK, pgvector(Hibernate 타입 매핑)
- **프론트 구성 미확정**: 별도 Next 레포로 갈지 Thymeleaf+htmx로 갈지 재논의 중 (2026-08-02 시점)
- git 저장소 미초기화

## 주의

**Boot 4 기준.** 의존성 좌표가 Boot 3과 다르다 (`spring-boot-starter-web` → `spring-boot-starter-webmvc`).
Boot 3 문서를 그대로 따르지 말 것.
