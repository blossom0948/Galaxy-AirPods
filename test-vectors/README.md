# BLE test vectors

실제 Galaxy에서 사용자가 직접 수집하고 주소/식별자를 마스킹한 packet만 저장한다. 각 vector에는 모델/상태/동일 시각의 참조 배터리 값을 함께 기록하고, 추측한 byte offset은 넣지 않는다.

현재는 검증된 vector가 없다. 따라서 parser는 Apple manufacturer packet을 발견해도 배터리 값을 반환하지 않는다.
