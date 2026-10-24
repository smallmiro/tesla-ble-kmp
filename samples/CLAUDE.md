@AGENTS.md

# samples/CLAUDE.md — Claude Code 전용 추가 지침 (샘플)

| 작업 | 에이전트 | 스킬 |
|---|---|---|
| Android 샘플 구현 | `android-developer` | `sample-from-lib-docs`, `superpowers:test-driven-development` |
| iOS 샘플 구현 | `ios-developer` | `sample-from-lib-docs`, `superpowers:test-driven-development` |
| 라이브러리 API 부족·문서 불일치 발견 | (발견한 에이전트) | `library-api-request` |
| 샘플 문서 HTML ko/ja 번역 | `docs-translator` | – |
| 모니터 로그 분석 | `protocol-analyst` | `analyze-monitor-logs` |

- 서브에이전트에는 작업 1개 + 관련 `SFR-xxx` + 참고할 `{{LIB_DOCS_DIR}}` 페이지와 절 + "문서에 사용 가능으로 적힌 `:sdk` 공개 API만 쓴다"는 조건을 전달합니다(루트 `CLAUDE.md` §3).
