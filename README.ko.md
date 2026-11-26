<p align="center">
  <img src="docs/assets/doclens-banner.png" alt="DocLens" width="760">
</p>

<p align="center">
  <em>AI 에이전트를 위한, 초점 맞춘 공식 문서.</em>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/status-early_development-A6E15E?style=flat-square&labelColor=0E1512" alt="Status: early development">
  <img src="https://img.shields.io/badge/java-21-F3E7D3?style=flat-square&labelColor=0E1512" alt="Java 21">
  <img src="https://img.shields.io/badge/spring_boot-4.1-F3E7D3?style=flat-square&labelColor=0E1512" alt="Spring Boot 4.1">
  <img src="https://img.shields.io/badge/python-3.13-F3E7D3?style=flat-square&labelColor=0E1512" alt="Python 3.13">
  <img src="https://img.shields.io/badge/license-MIT-F3E7D3?style=flat-square&labelColor=0E1512" alt="MIT license">
</p>

<p align="center">
  <a href="#왜-필요한가">왜 필요한가</a> ·
  <a href="#동작-방식">동작 방식</a> ·
  <a href="#진행-상황">진행 상황</a> ·
  <a href="#저장소-구성">구성</a> ·
  <a href="#소스에서-빌드">빌드</a> ·
  <a href="#라이선스">라이선스</a>
</p>

<p align="center">
  <sub><a href="README.md">English</a> · 한국어</sub>
</p>

---

에이전트가 웹에 Spring Security 7을 묻습니다. 웹은 `WebSecurityConfigurerAdapter`를 쓰는 2019년 블로그 글로 답합니다. 에이전트는 그 글을 믿습니다.

DocLens는 프로젝트가 쓰는 바로 그 버전의 Spring 공식 문서를, 미리 수집·정제·색인한 로컬 파일로 에이전트에게 건넵니다. 모든 결과에는 출처(URL, 제목 경로, 버전)가 붙습니다. 에이전트는 맞는 버전의 맞는 쪽을 읽고, 어디서 읽었는지 밝힙니다.

> [!NOTE]
> DocLens는 개발 초기 단계입니다. Pack 형식과 이를 검증하는 Reader는 완성됐고, MCP 서버·Agent Skill·수집 파이프라인은 만드는 중입니다. *예정*으로 표시된 기능은 아직 동작하지 않습니다.

## 왜 필요한가

코딩 에이전트에게 Spring API를 물으면 보통 셋 중 하나를 합니다. 여러 주 버전이 섞인 학습 기억으로 답하거나, 그럴듯한 결과가 나올 때까지 웹 검색을 반복하거나, 문서 페이지를 통째로 컨텍스트에 넣습니다. 셋 모두 토큰과 시간이 들고, 어느 쪽도 버전이 `build.gradle`과 맞는지 확인하지 않습니다.

DocLens는 반복되는 일을 모델 밖으로 옮깁니다.

| 코드가 하는 일 | 에이전트에 남는 일 |
|---|---|
| 공식 문서를 미리 수집·정제·색인 | 질문의 의도 이해 |
| 프로젝트 버전과 설치된 Pack의 일치 확인 | 출처가 실제로 답을 뒷받침하는지 판단 |
| 인용한 출처의 존재 확인 | 여러 출처를 이어 설명 |
| 관련 절만 컨텍스트 예산 안에서 반환 | 문서로 확인되지 않은 내용을 그대로 밝히기 |

출처가 존재한다는 것과 그 출처가 주장을 뒷받침한다는 것은 다릅니다. DocLens는 앞의 것을 확인하고, 뒤의 판단은 여전히 에이전트의 몫입니다.

## 동작 방식

```
 build side (maintainer)                         your machine
┌────────────────────────────────────┐          ┌──────────────────────────────────────┐
│ scheduler   when to collect        │          │ coding agent (Claude Code, Codex ..) │
│ collector   fetch official docs    │          │   │  Agent Skill: how to research    │
│ pipeline    parse · chunk · index  │          │   ▼                                  │
│             export one Pack        │──Pack──▶ │ mcp-server   read-only, local, STDIO │
└────────────────────────────────────┘          │   ▼                                  │
                                                │ spring-security@7.0.2.pack (SQLite)  │
                                                └──────────────────────────────────────┘
```

- **Knowledge Pack.** 문서 버전마다 SQLite 파일 하나입니다. 문서, 섹션, 청크, 코드 변형, 식별자, 링크, 전문 검색 색인이 들어 있습니다. 논리 내용 해시로 빌드가 달라도 같은 내용인지 알 수 있습니다. Reader는 검색 전에 Pack 전체를 검증합니다.
- **로컬 MCP 서버** *(예정)*. 사용자 PC에서 STDIO로 실행되고, 설치된 Pack만으로 답합니다. 원격 검색 서비스가 없어 검색 요청이 PC 밖으로 나가지 않습니다. 읽기 전용이라 프로젝트 파일을 고치거나 셸 명령을 실행하지 않습니다.
- **Agent Skill** *(예정)*. 어떤 버전을 확인하고, 예산 안에서 어떻게 검색하고, 어떻게 인용할지 에이전트에게 알려 주는 프롬프트 파일입니다. 별도 LLM API 없이 사용자의 에이전트가 직접 씁니다.

예정된 검색 결과 형태(예시):

```
spring-security@7.0.2 · Servlet Applications › Configuration › Java Configuration
https://docs.spring.io/spring-security/reference/...
  @Bean
  SecurityFilterChain filterChain(HttpSecurity http) throws Exception { ... }
```

## 진행 상황

| 단계 | 내용 | 상태 |
|---|---|---|
| A | 언어 간 계약, Pack v1 Writer와 검증 Reader | 완료 |
| B | `mcp-server`: 설치된 Pack 검색 | 다음 |
| C | `pipeline`: 공식 문서 파싱·청킹 | 예정 |
| D | `pipeline`: 저장과 Pack 내보내기 | 예정 |
| E | `collector`: 공식 문서 수집 | 예정 |
| F | `scheduler-server`와 로컬 전체 실행 | 예정 |
| G | AWS 배포 | 예정 |

첫 릴리스 `v0.1.0`은 MCP 서버가 Pack을 검색할 수 있게 되는 시점에 낼 예정입니다.

## 저장소 구성

| 경로 | 내용 |
|---|---|
| `knowledge-core/` | Pack 형식: canonical JSON, Pack Writer·Reader. `pipeline`과 `mcp-server`가 함께 사용 |
| `mcp-server/` | 로컬 STDIO MCP 서버 *(예정)* |
| `pipeline/` | 파싱, 청킹, 저장, Pack 내보내기 *(예정)* |
| `scheduler-server/` | 수집·가공 일정 관리 *(예정)* |
| `collector/` | 공식 문서 Python 수집기 *(예정)* |
| `contracts/` | 언어 간 공유 JSON Schema, 예시, golden 값 |
| `tools/` | 계약 검사 스크립트 |
| `maintenance-cli/` | 운영 도구 *(예정)* |

## 소스에서 빌드

JDK 21과 [uv](https://docs.astral.sh/uv/)가 필요합니다.

```bash
./gradlew build
```

```bash
cd collector && uv sync && uv run pytest
```

```bash
uv run --no-project --python 3.13 --with jsonschema==4.26.0 python tools/check_contracts.py
```

## 로컬 스택 실행

Docker와 Compose가 필요합니다. `.env.example`을 `.env`로 복사해 collector와 S3 값을 채우고, 아래 명령은 모두 저장소 루트에서 실행합니다.

1. 토큰: `scheduler`와 `operator`마다 한 번씩 실행합니다. scheduler 실행의 첫 줄은 `.env`의 `DOCLENS_SCHEDULER_TOKEN`에, operator 첫 줄은 셸의 `DOCLENS_OPERATOR_TOKEN`에 두고, 둘째 줄로 `DOCLENS_CALLER_TOKENS=scheduler:<hash>,operator:<hash>`를 채웁니다.

   ```bash
   python3 -c "import secrets,hashlib;t=secrets.token_urlsafe(32);print(t);print(hashlib.sha256(t.encode()).hexdigest())"
   ```

2. 요청 틀: `registry import`, `probe`, `approve`로 원본을 승인한 뒤 만듭니다.

   ```bash
   mkdir -p .local && (cd collector && uv run python -m collector request <sourceId>...) > .local/collection-request.json
   ```

3. 띄운 뒤 `docker compose logs pipeline | grep 'processing config'`의 `sha256:…`를 `.env`의 `DOCLENS_SCHEDULER_PROCESSING_CONFIG_HASH`에 넣고 scheduler를 다시 띄웁니다(`docker compose --profile app up -d scheduler`).

   ```bash
   ./gradlew :pipeline:bootJar :scheduler-server:bootJar
   docker compose --profile app --profile worker up -d --wait
   ```

4. 지금 수집(출력 `{"result":"CREATED|DUPLICATE_SLOT|ACTIVE_RUN_EXISTS","runId":…}`):

   ```bash
   docker compose run --rm scheduler --spring.profiles.active=command collect-now <label>
   ```

pipeline과 scheduler 컨테이너는 `pipeline/build/libs`, `scheduler-server/build/libs`의 jar를 그대로 실행합니다. 스택이 도는 동안 jar를 다시 빌드하지 말고, 먼저 `docker compose --profile app --profile worker down`으로 내립니다.

## 라이선스

[MIT](LICENSE)

DocLens는 Spring 프로젝트나 Broadcom과 관계가 없으며 그들의 보증을 받지 않습니다. Spring 문서는 각자의 라이선스를 따릅니다.
