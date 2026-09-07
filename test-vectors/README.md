# BLE test vectors

실제 Galaxy에서 사용자가 직접 수집하고 주소/식별자를 마스킹한 packet만 저장한다. 각 vector에는 모델/상태/동일 시각의 참조 배터리 값을 함께 기록하고, 추측한 byte offset은 넣지 않는다.

프로토콜 회귀 테스트에는 paired mode(0x07/0x19)와 pairing mode(0x07/0x0E) 입력을 사용한다. 실제 Galaxy에서 수집한 값은 주소와 식별자를 마스킹한 뒤 추가한다.
