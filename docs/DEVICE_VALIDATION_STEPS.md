# 실기기 검증 절차

현재 문서와 코드는 실측 packet 없이 AirPods 배터리 값을 추측하지 않는다. 아래 순서로 Galaxy와 실제 AirPods에서 raw advertising sample을 수집한다.

1. debug APK를 Galaxy에 설치한다.
2. `Debug` 화면에서 Bluetooth/Nearby devices 권한을 허용하고 `Start scan`을 누른다.
3. 케이스를 닫은 상태에서 5~10초 수집한다.
4. 케이스를 열고 5~10초 수집한다.
5. 왼쪽 이어버드만 꺼낸 뒤 수집한다.
6. 오른쪽도 꺼낸 뒤 수집한다.
7. 한쪽을 케이스에 다시 넣고 충전 상태를 수집한다.
8. 유선/무선 충전 중 케이스 상태도 수집한다.
9. 같은 시각 iPhone/Apple 기기에 표시되는 L/R/Case 값을 기록한다.
10. `Copy masked diagnostic data` 또는 `Save diagnostic JSON`으로 주소가 마스킹된 자료를 보관한다.
11. `test-vectors/`에 동의한 샘플만 넣고 parser unit test를 추가한다.

검증 전에는 unknown/null을 실제 퍼센트로 대체하지 않는다.
