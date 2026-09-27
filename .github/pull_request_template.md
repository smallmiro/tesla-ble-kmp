## 무엇을 / 왜
<!-- 한두 줄. 추적 ID 필수 -->
Refs: FR-___ / NFR-___ / ADR-____

## 변경 종류
- [ ] struct (구조만, 동작 불변)
- [ ] feat / fix (동작 변경)
- [ ] docs / test / chore
> 구조 변경과 동작 변경이 한 커밋에 섞이지 않았는지 확인하세요 (Tidy First).

## 원본 대응
<!-- 참고한 개발 매뉴얼 문서 (documents/md/...) -->
<!-- 포팅/수정한 동작의 Go 원본 경로 (vehicle-command@<커밋> ...) 또는 "해당 없음" -->
<!-- 매뉴얼과 원본의 불일치가 있었다면 내용 -->

## 체크리스트 (docs/workflow.md §9 DoD)
- [ ] 실패 테스트로 시작 → 통과 (Red → Green)
- [ ] `tools/ci/verify.sh` 통과(로컬), 경고 0
- [ ] 원본 Go 동작과 같음 (다르면 주석과 ADR에 이유 기록)
- [ ] 로그와 픽스처에 키, VIN 원문 없음
- [ ] 공개 API 변경 시 `apiDump`, KDoc, 라이브러리 사용 문서(`{{LIB_DOCS_DIR}}`) 갱신
- [ ] 요구사항·설계 변경 시 PRD, SDD, ADR 갱신
- [ ] 의존성 변경 시 `architecture` 라벨, 암호·키·로그 변경 시 `security` 라벨

## AI 코드 리뷰
<!-- 사용한 도구, 지적 사항 요약, 처리 결과 (반영 / 반박 사유) -->
