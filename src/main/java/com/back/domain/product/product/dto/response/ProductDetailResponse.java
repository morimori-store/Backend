package com.back.domain.product.product.dto.response;

import com.back.domain.product.category.entity.Category;
import com.back.domain.product.product.entity.AdditionalProduct;
import com.back.domain.product.product.entity.Option;
import com.back.domain.product.product.entity.ProductImage;
import com.back.domain.product.tag.dto.response.TagResponse;
import com.back.global.s3.FileType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 상품 상세 조회 응답 DTO
 */
@Schema(name = "ProductDetailResponse", description = "상품 상세 조회 응답 DTO")
public record ProductDetailResponse(
        @Schema(description = "상품 UUID", example = "550e8400-e29b-41d4-a716-446655440000")
        UUID productUuid,

        @Schema(description = "작가명", example = "김작가")
        String artistName,

        @Schema(description = "브랜드명", example = "문구브랜드")
        String brandName,

        @Schema(description = "상품명", example = "벚꽃 키링")
        String name,

        @Schema(description = "평균 평점", example = "4.6")
        double averageRating,

        @Schema(description = "리뷰 개수", example = "42")
        int reviewCount,

        @Schema(description = "정가", example = "10000")
        int price,

        @Schema(description = "할인율", example = "10")
        int discountRate,

        @Schema(description = "할인된 가격", example = "9000")
        int discountPrice,

        @Schema(description = "묶음 배송 가능 여부", example = "true")
        boolean bundleShippingAvailable,

        @Schema(description = "기본 배송비", example = "3000")
        int deliveryCharge,

        @Schema(description = "배송비 유형(FREE(무료배송)|CONDITIONAL_FREE(조건부 무료배송)|PAID(유료배송))", example = "CONDITIONAL_FREE")
        String deliveryType,

        @Schema(description = "조건부 무료 기준 금액(null 가능)", example = "30000")
        Integer conditionalFreeAmount,

        @Schema(description = "제주 추가 배송비", example = "5000")
        int additionalShippingCharge,

        @Schema(description = "옵션 목록(null 가능)")
        List<OptionResponse> options,

        @Schema(description = "추가 상품 목록(null 가능)")
        List<AdditionalProductResponse> additionalProducts,

        @Schema( description = "이미지 파일 목록", example = "[" + "{\"url\":\"https://example.com/image1.jpg\",\"type\":\"MAIN\",\"s3Key\":\"product-images/uuid1.png\",\"originalFileName\":\"example.png\"}," + "{\"url\":\"https://example.com/image2.jpg\",\"fileType\":\"ADDITIONAL\",\"s3Key\":\"product-images/uuid2.png\",\"originalFileName\":\"example2.png\"}," + "]" )
        List<ProductImageResponse> images,

        @Schema(description = "상품 필수 정보")
        ProductEssentialInfo essentialInfo,

        @Schema(description = "재고", example = "100")
        int stock,

        @Schema(description = "상품 상세 설명(HTML 포함 가능)")
        String description,

        @Schema(description = "최소 구매 수량", example = "1")
        int minQuantity,

        @Schema(description = "최대 구매 수량", example = "5")
        int maxQuantity,

        @Schema(description = "판매 상태(BEFORE_SELLING(판매 전)|SELLING(판매 중)|SOLD_OUT(품절)|END_OF_SALE(판매 종료))(null 가능)", example = "SELLING")
        String sellingStatus,

        @Schema(description = "전시 상태(BEFORE_DISPLAY(전시 전)|DISPLAYING(전시 중)|END_OF_DISPLAY(전시 종료))(null 가능)", example = "DISPLAYING")
        String displayStatus,

        @Schema(description = "기획상품 여부", example = "false")
        boolean isPlanned,

        @Schema(description = "재입고 상품 여부", example = "true")
        boolean isRestock,

        @Schema(description = "태그 ID 목록", example = "[1,2,3]")
        List<TagResponse> tags,

        @Schema(description = "판매 시작일", example = "2025-10-01T10:00:00")
        LocalDateTime sellingStartDate,

        @Schema(description = "판매 종료일", example = "2025-12-01T10:00:00")
        LocalDateTime sellingEndDate,

        @Schema(description = "카테고리 ID", example = "1")
        Long categoryId
) {
    @Schema(name = "OptionResponse", description = "상품 옵션")
    public record OptionResponse(
            @Schema(description = "옵션명", example = "벚꽃키링a버전")
            String optionName,
            @Schema(description = "옵션 재고", example = "10")
            int optionStock,
            @Schema(description = "옵션 가격 조정", example = "500")
            int optionAdditionalPrice
    ) {
        public static OptionResponse fromEntity(Option option) {
            return new OptionResponse(
                    option.getOptionName(),
                    option.getOptionStock(),
                    option.getOptionAdditionalPrice()
            );
        }
    }

    @Schema(name = "AdditionalProductResponse", description = "추가 상품")
    public record AdditionalProductResponse(
            @Schema(description = "추가 상품명", example = "장미 키링")
            String name,
            @Schema(description = "추가 상품 재고", example = "20")
            int stock,
            @Schema(description = "추가 상품 가격", example = "2000")
            int price
    ) {
        public static AdditionalProductResponse fromEntity(AdditionalProduct additional) {
            return new AdditionalProductResponse(
                    additional.getAdditionalName(),
                    additional.getAdditionalStock(),
                    additional.getAdditionalPrice()
            );
        }
    }

    @Schema(name = "ProductImageResponse", description = "상품 이미지")
    public record ProductImageResponse(
            @Schema(description = "이미지 URL", example = "https://s3.amazonaws.com/bucket/uuid-main.jpg")
            String url,
            @Schema(description = "이미지 타입", example = "MAIN")
            FileType type,
            @Schema(description = "이미지 s3Key", example = "product-images/uuid1.png")
            String s3Key,
            @Schema(description = "이미지 원본파일명", example = "example.png")
            String originalFileName
    ) {
        public static ProductImageResponse fromEntity(ProductImage img) {
            return new ProductImageResponse(
                    img.getFileUrl(),
                    img.getFileType(),
                    img.getS3Key(),
                    img.getOriginalFilename()
            );
        }
    }

    @Schema(name = "ProductEssentialInfo", description = "상품 필수 정보")
    public record ProductEssentialInfo(
            @Schema(description = "품명 및 모델명", example = "MM-CHERRY-24")
            String productModelName,

            @Schema(description = "법에 의한 인증 여부", example = "true")
            boolean certification,

            @Schema(description = "제조국", example = "대한민국")
            String origin,

            @Schema(description = "재질", example = "면 100%")
            String material,

            @Schema(description = "사이즈", example = "12x30x5cm")
            String size,

            @Schema(description = "제조자", example = "(주) 문구문구")
            String businessName,

            @Schema(description = "사업자 등록 번호", example = "123-45-67890")
            String businessNumber,

            @Schema(description = "대표자명", example = "김작가")
            String ownerName,

            @Schema(description = "A/S 책임자 / 전화번호", example = "(주)문구문구/010-1234-5678")
            String asManager,

            @Schema(description = "전자우편주소", example = "example@company.com")
            String email,

            @Schema(description = "사업장 소재지", example = "서울특별시 강남구 테헤란로 1길 100 201호")
            String businessAddress,

            @Schema(description = "통신 판매업 신고 번호", example = "2023-서울강남-0001")
            String telecomSalesNumber
    ) {}
}
