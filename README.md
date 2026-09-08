# AirPods Galaxy

Galaxy에서 AirPods의 실제 BLE 상태를 확인하는 Android 앱입니다.

## 동작

- Apple 제조사 ID 0x004C의 AirPods 광고 패킷을 스캔합니다.
- 지원 모델의 L/R/케이스 배터리와 충전·케이스 상태를 표시합니다.
- 앱을 열지 않아도 포그라운드 서비스가 감지를 유지하고, 위젯·알림·오버레이를 갱신합니다.
- 설정에서 자동 감지 또는 AirPods 세대/Pro/Max 모델을 선택할 수 있습니다.
- 앱과 백그라운드 감지 서비스가 GitHub의 update.json을 주기적으로 확인하고, raw/API 결과 중 최신 manifest를 선택한 뒤 새 Release APK를 검증하고 자동으로 다운로드·설치를 시작합니다.

지원 모델 모양은 AirPods 1·2·3·4, AirPods 4 ANC, AirPods Pro 1·2·2 USB-C·3, AirPods Max·Max USB-C·Max 2입니다.

## 실행

Android Studio에서 JDK 17과 Android SDK 35로 app을 실행합니다.

1. Bluetooth/Nearby devices와 알림 권한을 허용합니다.
2. AirPods 케이스를 열거나 이어버드를 사용 중인 상태에서 앱을 엽니다.
3. 다른 앱 위 팝업이 필요하면 설정에서 오버레이 권한을 허용합니다.
4. 홈 화면에 위젯을 추가하면 마지막으로 실제 감지된 배터리를 표시합니다.

데이터를 아직 받은 적이 없으면 배터리는 --로 표시됩니다. 임의의 미리보기 배터리 값은 사용하지 않습니다.

## 업데이트 배포

update.json의 versionCode가 현재 앱보다 높으면 앱이 자동으로 다운로드·설치를 시작합니다. APK는 GitHub Release에 AirPodsGalaxy.apk 이름으로 업로드해야 합니다. Android가 설치 확인을 요구하는 경우 시스템 알림을 한 번 눌러 마무리해야 합니다.

업데이트 APK는 최초 설치 APK와 같은 서명 키로 빌드해야 Android가 기존 앱을 교체할 수 있습니다. Android 보안 정책상 설치 단계에서는 사용자 확인이 표시될 수 있습니다.

## 프로젝트 구조

app/src/main/java/com/galaxyairpods/
  data/bluetooth       BLE 스캐너와 AirPods packet parser
  data/persistence     DataStore 저장
  domain/model         AirPods와 팝업 상태
  domain/popup         팝업 이벤트 상태기계
  service              백그라운드 감지와 오버레이
  ui/components        모델별 제품 렌더러·배터리·팝업
  widget               홈 화면 위젯
  update               GitHub Release 기반 업데이트
