# AirPods Galaxy 구현 계획

## 문서 해석

`AirPods_Galaxy_Codex_PROJECT_SPEC_v3_Reference_Analyzed.md`는 이 저장소의 제품/기술 사양으로 사용한다. Apple 레퍼런스 링크와 V3의 모션 수치는 Apple의 비공개 내부 값이 아니라, Galaxy에서 독자적으로 튜닝할 초기 탐색 기준으로만 취급한다.

사용자 요청의 핵심은 Galaxy에서 AirPods의 좌/우/케이스 배터리를 확인하고, 케이스를 열었을 때 빠르고 자연스러운 상태 팝업과 홈 화면 위젯으로 확인할 수 있게 하는 것이다.

## 현재 구현 범위

1. Kotlin + Jetpack Compose + Material 3 Android 프로젝트 골격
2. `AirPodsState`와 confidence/stale 모델
3. 홈 화면: 제품 상태, L/R/Case 배터리, 충전 상태, 마지막 갱신 시각
4. V3 MotionTokens와 interruptible PopupMotionController
5. Preview Lab: pairing/known-device/connected/removed/charging/low-battery/interrupt 시나리오
6. 실시간 motion controls와 candidate JSON 저장
7. BLE Debug 화면: 권한, raw advertising bytes, masked address, parser match 상태
8. 권한/오버레이 상태 및 Android fallback 안내
9. Glance 위젯 receiver와 2x2/4x2/4x1 표시 구조
10. 실기기 검증이 필요한 parser는 임의 byte offset을 확정하지 않고 `NEEDS_DEVICE_VALIDATION`으로 표시

## 후속 실기기 작업

- 실제 AirPods 한 모델에서 익명화된 BLE test vector 확보
- vector 기반 parser와 unit test 추가
- Galaxy 60/120Hz, One UI, Fold 화면에서 motion/jank 측정
- 실제 overlay/foreground service 정책 검증
- 자체 제작 product asset을 placeholder renderer와 교체

빌드 머신에 Android SDK/JDK가 없으면 소스 생성까지 진행하고, Android Studio 또는 JDK 17 + SDK 설치 후 Gradle 검증을 수행한다.
