package com.back.domain.dashboard.artist.dto.response;

import com.back.global.util.PageResponse;

import java.util.UUID;

/**
 * 작가 상품 관련 응답 DTO
 *
 * 작가의 상품 목록과 관련된 정보를 포함합니다.
 * 2025.09.29 수정 - Product DTO 필드 변경 (실제 DB 연동)
 */
public class ArtistProductResponse {

    /**
     * 상품 목록 응답
     */
    public static class List extends PageResponse<ArtistProductResponse.Product> {

        public List() {
            super();
        }

        public List(java.util.List<Product> content, int page, int size,
                    long totalElements, int totalPages, boolean hasNext, boolean hasPrevious) {
            super(content, page, size, totalElements, totalPages, hasNext, hasPrevious);
        }
    }

    /**
     * 상품 정보
     */
    public record Product(
            /** 상품 UUID */
            UUID productUuid,
            /** 상품명 */
            String productName,
            /** 가격 */
            int price,
            /** 할인율 */
            int discountRate,
            /** 할인가 */
            int discountPrice,
            /** 판매 상태 코드 */
            String sellingStatus,
            /** 판매 상태 텍스트 */
            String statusText,
            /** 등록일 */
            String registrationDate
    ) {}
}
