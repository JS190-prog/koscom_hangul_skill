# 양식별 기본 문체 (templates/voice)

대표 문서를 주지 않았을 때 쓰는 양식별 문체 기준이다. `scripts/voice/voice_tool.py build`로 만든다.

- 대표 문서는 **사람이 쓴 문서만** 쓴다(`--human`). 합성본은 기준이 AI 문체가 되므로 넣지 않는다.
- 대표 문서에는 실제 사내 문서(가명화본 포함)가 들어간다. build 결과 중 원문이 그대로 남는 곳은 저장소에 두지 않는다.
  - `style-guide.md`의 "7. 예문" 목록: 안내 한 줄로 바꾼다
  - `style-profile.json`의 `analysis.examples`: 빈 목록으로 둔다
  - `corpus/` 폴더: 커밋하지 않는다
- 측정값과 규칙(`fingerprint-slim.json`, 끝맺음 비율, 자주 쓴 끝말·두 어절 표현)은 원문이 아니므로 그대로 둔다.
- 다시 build 했으면 커밋하기 전에 위 세 곳을 확인한다.
