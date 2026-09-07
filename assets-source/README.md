# Product asset pipeline

현재 앱은 자체 Canvas placeholder renderer로 기능과 모션을 검증한다.

최종 에셋을 만들 때 다음 layer를 별도로 제작한다.

- `case_closed`
- `case_body_layer`
- `case_lid_layer` (hinge pivot 포함)
- `left_bud_layer`
- `right_bud_layer`

Apple 내부 리소스, Apple 웹 제품 사진, 타 companion 앱의 asset은 사용하지 않는다. 자체 모델/렌더 또는 사용 권리가 확인된 asset만 Android runtime asset으로 변환한다.
