# Motion reverse engineering log

이 문서는 iPhone 영상이나 실기기 비교 결과를 기록하기 위한 템플릿이다. Apple 내부 spring/duration 값으로 해석하지 않는다.

## Capture fields

- fps:
- source / device:
- scene: FIRST_PAIRING / KNOWN_DEVICE_STATUS
- popup_first_visible_frame:
- popup_settle_frame:
- product_first_visible_frame:
- product_settle_frame:
- battery_first_visible_frame:
- battery_settle_frame:
- status_change_frame:
- exit_start_frame:
- exit_end_frame:

좌표는 popup 높이에 대해 정규화한다.

```text
normalizedY = (currentY - finalY) / popupHeight
normalizedScale = currentScale / finalScale
progress = frame / totalFrames
```

현재 코드의 값은 `MotionTokens.kt`의 Galaxy 탐색 baseline이며, 각 값은 candidate JSON으로 저장할 수 있다.
