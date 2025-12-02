package com.back.domain.product.product.dto.request;

import com.back.global.s3.S3FileRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 상품 수정 요청을 받는 DTO
 */
@Schema(name = "UpdateProductRequest", description = "상품 수정 요청 DTO")
public record UpdateProductRequest(

        @Schema(description = "카테고리 ID", example = "1")
        @NotNull(message = "카테고리 ID는 필수입니다.")
        Long categoryId,

        @Schema(description = "상품명", example = "벚꽃 키링")
        @NotBlank(message = "상품명은 필수입니다.")
        String name,

        @Schema(description = "브랜드명", example = "문구브랜드")
        @NotBlank(message = "브랜드명은 필수입니다.")
        String brandName,

        @Schema(description = "가격", example = "10000")
        @Min(value = 1, message = "정가는 최소 1 이상이어야 합니다.")
        int price,

        @Schema(description = "할인율 (%)", example = "10")
        @Min(value = 0, message = "할인율은 0 이상이어야 합니다.")
        @Max(value = 100, message = "할인율은 100 이하이어야 합니다.")
        int discountRate,

        @Schema(description = "묶음 배송 가능 여부", example = "true")
        @NotNull(message = "묶음 배송 여부는 필수입니다.")
        Boolean bundleShippingAvailable,

        @Schema(description = "기본 배송비", example = "3000")
        @Min(value = 0, message = "기본 배송비는 최소 0 이상이어야 합니다.")
        int deliveryCharge,

        @Schema(description = "제주 추가 배송비", example = "5000")
        @Min(value = 0, message = "추가 배송비는 최소 0 이상이어야 합니다.")
        int additionalShippingCharge,

        @Schema(description = "배송비 유형 [ FREE(무료배송) | CONDITIONAL_FREE(조건부 무료배송) | PAID(유료배송) ]", example = "CONDITIONAL_FREE")
        @NotBlank(message = "배송비 유형은 필수입니다.")
        String deliveryType,

        @Schema(description = "조건부 무료 기준 금액 (null 가능)", example = "30000")
        Integer conditionalFreeAmount,

        @Schema(description = "재고 수량", example = "100")
        @Min(value = 1, message = "재고는 최소 1 이상이어야 합니다.")
        int stock,

        @Schema(description = "상품 상세 설명", example = "설명텍스트+이미지태그")
        @NotBlank(message = "상품 상세 설명은 필수입니다.")
        String description,

        @Schema(description = "판매 상태 [ BEFORE_SELLING(판매 전) | SELLING(판매 중) | SOLD_OUT(품절) | END_OF_SALE(판매 종료) ] (null이면 기본값 SELLING)", example = "SELLING")
        String sellingStatus,

        @Schema(description = "전시 상태 [ BEFORE_DISPLAY(전시 전) | DISPLAYING(전시 중) | END_OF_DISPLAY(전시 종료) ] (null이면 기본값 DISPLAYING)", example = "DISPLAYING")
        String displayStatus,

        @Schema(description = "최소 구매 수량", example = "1")
        @Min(value = 1, message = "최소 구매 수량은 1 이상이어야 합니다.")
        int minQuantity,

        @Schema(description = "최대 구매 수량", example = "5")
        @Min(value = 1, message = "최대 구매 수량은 1 이상이어야 합니다.")
        int maxQuantity,

        @Schema(description = "기획상품 여부", example = "false")
        @NotNull(message = "기획상품 여부는 필수입니다.")
        Boolean isPlanned,

        @Schema(description = "재입고 상품 여부", example = "true")
        @NotNull(message = "재입고 여부는 필수입니다.")
        Boolean isRestock,

        @Schema(description = "판매 시작일 (null 가능)", example = "2025-10-01T10:00:00")
        LocalDateTime sellingStartDate,

        @Schema(description = "판매 종료일 (null 가능)", example = "2025-12-01T10:00:00")
        LocalDateTime sellingEndDate,

        @Schema(description = "태그 ID 목록", example = "[1,2,3]")
        @NotEmpty(message = "스타일 태그는 최소 1개 이상이어야 합니다.")
        List<Long> tags,

        @Schema(description = "옵션 목록 (null 가능)")
        @Valid
        List<Option> options,

        @Schema(description = "추가 상품 목록 (null 가능)")
        @Valid
        List<AdditionalProduct> additionalProducts,

        @Schema(description = "이미지 파일 목록", example = "[{\"url\":\"https://example.com/image1.jpg\",\"type\":\"MAIN\",\"s3Key\":\"s3-key-1\",\"originalFileName\":\"image1.jpg\"}]")
        @NotEmpty(message = "이미지는 최소 1개 이상이어야 합니다.")
        @Valid
        List<S3FileRequest> images,

        @Schema(description = "품명/모델명", example = "ABC-123")
        @NotBlank(message = "품명/모델명은 필수입니다.")
        String productModelName,

        @Schema(description = "KC 인증 여부", example = "true")
        @NotNull(message = "KC 인증 여부는 필수입니다.")
        Boolean certification,

        @Schema(description = "제조국", example = "대한민국")
        @NotBlank(message = "제조국은 필수입니다.")
        String origin,

        @Schema(description = "재질", example = "면 100%")
        @NotBlank(message = "재질은 필수입니다.")
        String material,

        @Schema(description = "사이즈", example = "12x30x5cm")
        @NotBlank(message = "사이즈는 필수입니다.")
        String size

) {
    @Schema(description = "상품 옵션")
    public record Option(
            @Schema(description = "옵션명", example = "분홍색")
            @NotBlank(message = "옵션명은 필수입니다.")
            String optionName,

            @Schema(description = "옵션 재고", example = "10")
            @Min(value = 1, message = "옵션 재고는 1 이상이어야 합니다.")
            int optionStock,

            @Schema(description = "옵션 추가금", example = "5000")
            @Min(value = 0, message = "옵션 추가금은 0 이상이어야 합니다.")
            int optionAdditionalPrice
    ) implements BaseOption {}

    @Schema(description = "추가 상품")
    public record AdditionalProduct(
            @Schema(description = "추가 상품명", example = "장미 키링")
            @NotBlank(message = "추가상품명은 필수입니다.")
            String additionalProductName,

            @Schema(description = "추가 상품 재고", example = "30")
            @Min(value = 1, message = "추가상품 재고는 1 이상이어야 합니다.")
            int additionalProductStock,

            @Schema(description = "추가 상품 가격", example = "12000")
            @Min(value = 0, message = "추가상품 가격은 0 이상이어야 합니다.")
            int additionalProductPrice
    ) implements BaseAdditionalProduct {}
}

