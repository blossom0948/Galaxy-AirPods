# Platform limitations

- Android는 백그라운드에서 무제한 BLE scan과 임의 시점의 다른 앱 위 UI를 보장하지 않는다. overlay 권한, foreground service 정책, Samsung 배터리 최적화에 따라 자동 팝업 동작이 달라질 수 있다.
- AirPods의 L/R/Case 배터리 advertising packet은 모델/상태별 확인이 필요하다. 실측 자료가 없을 때 parser가 byte offset을 추측하지 않도록 했으며, 지원되지 않은 packet은 `NEEDS_DEVICE_VALIDATION`으로 남긴다.
- 이 저장소의 제품 렌더는 자체 제작된 단순 Canvas placeholder다. Apple 내부 3D asset이나 웹 제품 사진을 번들하지 않는다.
- Glance 위젯 갱신은 Android의 App Widget update 제한을 받는다. 앱이 실제로 관찰한 값을 DataStore에 저장한 뒤 widget refresh를 요청하는 방식으로 확장한다.
- 화면이 꺼져 있거나 보안 화면 위에서 overlay를 표시하지 않는 것은 의도된 fallback이다.
