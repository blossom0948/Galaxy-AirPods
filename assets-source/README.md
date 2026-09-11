# Product asset pipeline

현재 앱은 자체 Canvas renderer를 기본 fallback으로 유지하고, 검증된 모델에는
독립적인 투명 WebP layer artwork를 사용한다.

현재 포함된 raster set:

- `generated/airpods_pro2_reference.png`: AirPods Pro 2 USB-C용 원본 합성 참고 이미지
- `app/src/main/res/drawable-nodpi/airpods_pro2_case_body.webp`: 열린 케이스 본체
- `app/src/main/res/drawable-nodpi/airpods_pro2_lid_open.webp`: 열린 뚜껑
- `app/src/main/res/drawable-nodpi/airpods_pro2_lid_closed.webp`: 닫힌 케이스 전환용 layer
- `app/src/main/res/drawable-nodpi/airpods_pro2_bud_left.webp`: 왼쪽 유닛
- `app/src/main/res/drawable-nodpi/airpods_pro2_bud_right.webp`: 오른쪽 유닛

이 artwork는 프로젝트용으로 새로 생성한 독립 렌더이며 Apple 내부 리소스,
Apple 웹 제품 사진, 타 companion 앱의 asset을 사용하지 않는다. 모델별로 실제
형상이 확인되지 않은 경우에는 기존 모델별 Canvas fallback을 사용한다.

최종 에셋을 만들 때 다음 layer를 별도로 제작한다.

- `case_closed`
- `case_body_layer`
- `case_lid_layer` (hinge pivot 포함)
- `left_bud_layer`
- `right_bud_layer`

Apple 내부 리소스, Apple 웹 제품 사진, 타 companion 앱의 asset은 사용하지 않는다. 자체 모델/렌더 또는 사용 권리가 확인된 asset만 Android runtime asset으로 변환한다.
