# AirPods Galaxy

Galaxy/Android에서 AirPods 상태를 확인하기 위한 독립형 companion 앱 프로토타입이다. 홈 화면에서 좌/우 이어버드와 케이스 배터리, 충전 상태, 마지막 갱신 시각을 보여주고, Preview Lab에서 케이스 팝업의 상태 변화와 모션을 실기기 없이 튜닝할 수 있다.

이 프로젝트는 Apple의 비공개 리소스나 제품 이미지를 포함하지 않는다. 제품 렌더는 자체 제작 placeholder Canvas이고, Apple 관련 표기는 호환 대상 설명에만 사용한다.

## 지원 범위

- Android 10(API 29) 이상을 우선 목표로 한다.
- 현재 앱 UI와 모션은 fake/preview 상태로 독립 검증할 수 있다.
- BLE parser는 raw packet을 수집하고 구조를 검증할 수 있는 진단 경로를 제공한다. 실측 test vector가 없는 모델은 배터리 값을 추측하지 않는다.

## 실행

Android Studio에서 프로젝트를 연 뒤 JDK 17과 Android SDK 35를 선택해 `app`을 실행한다.

1. 앱을 실행하고 Bluetooth/Nearby devices 권한을 허용한다.
2. `Preview`에서 `KNOWN_OPEN` 또는 `FIRST_PAIRING`을 눌러 팝업을 확인한다.
3. 실제 패킷을 확인하려면 `Debug`에서 `Start scan`을 누르고 [DEVICE_VALIDATION_STEPS.md](docs/DEVICE_VALIDATION_STEPS.md)의 순서로 수집한다.
4. 홈 화면 위젯을 추가하면 저장된 최신 상태를 표시한다. 아직 저장된 실제 상태가 없으면 배터리를 `--`로 표시한다.

## 권한과 제한

자동 팝업은 Android의 overlay 권한과 제조사 백그라운드 정책 영향을 받는다. 권한이 없으면 앱 내부 팝업/위젯은 계속 사용할 수 있고, 자동 팝업은 fallback 안내로 전환한다. 자세한 내용은 [PLATFORM_LIMITATIONS.md](docs/PLATFORM_LIMITATIONS.md)에 기록했다.

## 구조

```text
app/src/main/java/com/galaxyairpods/
  data/bluetooth       scanner + parser diagnostics
  data/persistence     DataStore mapping
  domain/model         AirPods and popup state
  domain/motion        reverse-engineering baseline tokens
  domain/popup         interruptible event controller
  ui/screens            Home / Preview / Debug / Settings
  ui/components         product renderer / battery / popup surface
  widget                Glance widget
```

## 상태 데이터 원칙

`null`은 확인할 수 없는 값이고 `0`은 실제 0%다. `LIVE`, `RECENT`, `STALE`, `UNKNOWN` confidence를 UI에 함께 사용해 오래된 값을 실시간 값처럼 표시하지 않는다.

## 아직 완료로 표시하지 않는 항목

실제 AirPods 모델별 L/R/Case BLE parsing과 Galaxy 실기기 성능 검증은 하드웨어와 익명화된 packet sample이 필요하다. 이 저장소는 그 작업을 위한 debug surface와 parser interface를 먼저 제공한다.
