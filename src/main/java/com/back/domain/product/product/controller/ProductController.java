package com.back.domain.product.product.controller;

import com.back.domain.product.product.dto.request.CreateProductRequest;
import com.back.domain.product.product.dto.request.UpdateProductRequest;
import com.back.domain.product.product.dto.response.ProductArtistInfoResponse;
import com.back.domain.product.product.dto.response.ProductDetailResponse;
import com.back.domain.product.product.dto.response.ProductListResponse;
import com.back.domain.product.product.dto.response.ShareLinkResponse;
import com.back.domain.product.product.entity.ProductImage;
import com.back.domain.product.product.service.ProductService;
import com.back.global.rsData.RsData;
import com.back.global.s3.FileType;
import com.back.global.s3.S3Service;
import com.back.global.s3.UploadResultResponse;
import com.back.global.s3.DescriptionImageUploadResponse;
import com.back.global.security.auth.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
@Tag(name = "상품", description = "상품 관련 API")
public class ProductController {

    private final ProductService productService;
    private final S3Service s3Service;

    /** 상품 등록 */
    @PostMapping
    @Operation(
            summary = "상품 등록",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "상품 등록 성공",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            example = """
                                                    {
                                                      "resultCode": "200",
                                                      "msg": "상품이 성공적으로 등록되었습니다.",
                                                      "data": "550e8400-e29b-41d4-a716-446655440000"
                                                    }
                                                    """
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "잘못된 요청 (필수값 누락, 존재하지 않는 카테고리/태그 등)",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            example = """
                                                    {
                                                      "resultCode": "400",
                                                      "msg": "상품명은 필수입니다.",
                                                      "data": null
                                                    }
                                                    """
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "403",
                            description = "권한 없음",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            example = """
                                                    {
                                                      "resultCode": "403",
                                                      "msg": "상품 등록 권한이 없습니다.",
                                                      "data": null
                                                    }
                                                    """
                                    )
                            )
                    )
            }
    )
    public ResponseEntity<RsData<UUID>> createProduct(
            @Valid @RequestBody CreateProductRequest request,
            @AuthenticationPrincipal CustomUserDetails customUserDetails) {
        UUID productUuid = productService.createProduct(request, customUserDetails);
        log.info("신규 상품 등록 성공: productUuid={}", productUuid);
        return ResponseEntity.ok(RsData.of("200", "상품이 성공적으로 등록되었습니다.", productUuid));
    }

    /** 상품 이미지 업로드 */
    @PostMapping("/images")
    @Operation(
            summary = "상품 이미지 업로드",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "files -> 업로드할 파일 리스트.  이미지(jpg, png 등), 문서(pdf, doc 등)<br>" +
                            "types -> 업로드할 파일 타입.  대표 이미지(MAIN), 그 외 이미지(ADDITIONAL), 썸네일 이미지(THUMBNAIL), 문서(DOCUMENT)",
                    required = true,
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "multipart/form-data",
                            schema = @Schema(
                                    type = "object",
                                    example = "{\n" +
                                            "  \"files\": [\"image1.jpg\", \"image2.jpg\"],\n" +
                                            "  \"types\": [\"MAIN\", \"THUMBNAIL\"]\n" +
                                            "}"
                            )
                    )
            ),
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "이미지 업로드 성공",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            example = """
                                                    {
                                                      "resultCode": "200",
                                                      "msg": "이미지 업로드 성공",
                                                      "data": [
                                                        {
                                                          "url": "https://bucket.s3.amazonaws.com/product-images/uuid1.png",
                                                          "type": "MAIN",
                                                          "s3Key": "product-images/uuid1.png",
                                                          "originalFileName": "example.png"
                                                        },
                                                        {
                                                          "url": "https://bucket.s3.amazonaws.com/product-images/uuid2.png",
                                                          "type": "THUMBNAIL",
                                                          "s3Key": "product-images/thumbnail-uuid2.png",
                                                          "originalFileName": "thumb_example.png"
                                                        }
                                                      ]
                                                    }
                                                    """
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "잘못된 요청 (파일/타입 누락, files/types 개수 불일치, S3 검증 실패 등)",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            example = """
                                                    {
                                                      "resultCode": "400",
                                                      "msg": "업로드할 파일이 없습니다.",
                                                      "data": null
                                                    }
                                                    """
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "500",
                            description = "파일 업로드 실패",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            example = """
                                                {
                                                  "resultCode": "500",
                                                  "msg": "파일 업로드 실패: example.png",
                                                  "data": null
                                                }
                                                """
                                    )
                            )
                    )
            }
    )
    public ResponseEntity<RsData<List<UploadResultResponse>>> uploadProductImages(
            @RequestPart List<MultipartFile> files,

            @Parameter(hidden = true)
            @RequestParam List<FileType> types) {
        List<UploadResultResponse> uploaded = s3Service.uploadFiles(files,"product-images", types);
        return ResponseEntity.ok(RsData.of("200","이미지 업로드 성공",uploaded));
    }

    /** 상품 정보에서 이미지 업로드 */
    @PostMapping("/description-images")
    @Operation(
            summary = "상품 정보에서 이미지 업로드",
            description = "상품 정보에 사용될 이미지를 업로드하고 URL 목록을 반환합니다.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "업로드할 파일 리스트",
                    required = true,
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "multipart/form-data"
                    )
            ),
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "이미지 업로드 성공",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            example = """
                                                    {
                                                      "resultCode": "200",
                                                      "msg": "이미지 업로드 성공",
                                                      "data": [
                                                        {
                                                          "fileUrl": "https://bucket.s3.amazonaws.com/product-images/uuid1.png"
                                                        },
                                                        {
                                                          "fileUrl": "https://bucket.s3.amazonaws.com/product-images/uuid2.png"
                                                        }
                                                      ]
                                                    }
                                                    """
                                    )
                            )
                    )
            }
    )
    public ResponseEntity<RsData<List<DescriptionImageUploadResponse>>> uploadDescriptionImages(
            @RequestPart List<MultipartFile> files) {
        List<DescriptionImageUploadResponse> response = s3Service.uploadFilesAndGetUrls(files, "product-images");
        return ResponseEntity.ok(RsData.of("200", "이미지 업로드 성공", response));
    }

    /** 상품 이미지 개별 삭제 (S3) */
    @DeleteMapping("/images")
    @Operation(
            summary = "S3 상품 이미지 개별 삭제",
            description = "s3Key를 사용하여 S3에 업로드된 상품 이미지를 삭제합니다. 상품 등록/수정 중 사용자가 업로드한 이미지를 다시 삭제할 때 사용됩니다.",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "파일 삭제 성공",
                            content = @Content(mediaType = "application/json", schema = @Schema(example = """
                                    {
                                      "resultCode": "200",
                                      "msg": "파일이 성공적으로 삭제되었습니다.",
                                      "data": "product-images/uuid1.png"
                                    }
                                    """))
                    )
            }
    )
    public ResponseEntity<RsData<String>> deleteProductImage(
            @Parameter(description = "삭제할 파일의 s3Key", required = true) @RequestParam String s3Key) {
        s3Service.deleteFile(s3Key);
        return ResponseEntity.ok(RsData.of("200", "파일이 성공적으로 삭제되었습니다.", s3Key));
    }

    /** 상품 이미지(파일) 다운로드 */
    @GetMapping("/images/download/{productUuid}")
    @Operation(
            summary = "상품 문서 다운로드 (테스트용)",
            description = "DOCUMENT 타입의 문서 파일 다운로드.<br>"+
                    "브라우저는 Content-Disposition 헤더를 보고 파일 다운로드 처리합니다.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "문서 다운로드 성공",
                            content = @Content(
                                    mediaType = "application/octet-stream",
                                    schema = @Schema(
                                            type = "string",
                                            format = "binary",
                                            example = "example-document.pdf"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "문서가 존재하지 않음",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            example = """
                                                {
                                                  "resultCode": "404",
                                                  "msg": "다운로드할 문서가 존재하지 않습니다.",
                                                  "data": null
                                                }
                                                """
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "500",
                            description = "파일 다운로드 실패",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            example = """
                                                {
                                                  "resultCode": "500",
                                                  "msg": "파일 다운로드 실패: product-document.pdf",
                                                  "data": null
                                                }
                                                """
                                    )
                            )
                    )
            }
    )
    public ResponseEntity<byte[]> downloadProductDocument(@PathVariable UUID productUuid) {
        // productUuid로 FileType이 DOCUMENT인 이미지 조회(사실상 다운로드 테스트용 API임. 이미지에 문서가 담길 일은 없을듯)
        ProductImage document = productService.getProductDocument(productUuid);
        // s3에서 파일 다운로드
        byte[] fileBytes = s3Service.downloadFile(document.getS3Key());
        //원본 파일명으로 응담
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + document.getOriginalFilename() + "\"") // 브라우저가 파일 다운로드 처리하게함
                .header("Content-Type", "application/octet-stream") // 바이너리 데이터임을 나타냄
                .body(fileBytes); // 실제 파일 바이트
    }

    /** 상품 목록 조회 */
    @GetMapping
    @Operation(
            summary = "상품 목록 조회",
            description = "전체 또는 카테고리별 상품 조회<br>" +
                    "필터링 [태그,가격대,배송 유형] / 정렬 [신상품순,가격낮은순,가격높은순,인기순] / 페이징",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "상품 목록 조회 성공",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            example = """
                                                {
                                                  "resultCode": "200",
                                                  "msg": "상품 목록 조회 성공",
                                                  "data": {
                                                    "page": 1,
                                                    "size": 10,
                                                    "totalElements": 23,
                                                    "totalPages": 3,
                                                    "products": [
                                                      {
                                                        "productUuid": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                                        "url": "https://bucket.s3.amazonaws.com/product-images/uuid1.png",
                                                        "brandName": "브랜드1",
                                                        "name": "상품1",
                                                        "price": 10000,
                                                        "discountRate": 10,
                                                        "discountPrice": 9000,
                                                        "rating": 4.6
                                                      }
                                                    ]
                                                  }
                                                }
                                                """
                                    )
                            )
                    )
            }
    )
    public ResponseEntity<RsData<ProductListResponse>> getProducts(
            @Parameter(description = "[필터링]카테고리 ID.  상위 / 하위 카테고리 각 조회 가능", example = "1")
            @RequestParam(required = false) Long categoryId,

            @Parameter(description = "[필터링]태그 ID 목록", example = "1,2")
            @RequestParam(required = false) List<Long> tagIds,

            @Parameter(description = "[필터링]최소 가격 설정", example = "3000")
            @RequestParam(required = false) Integer minPrice,

            @Parameter(description = "[필터링]최대 가격 설정",  example = "15000")
            @RequestParam(required = false) Integer maxPrice,

            @Parameter(description = "[필터링]배송비 유형.  유료 배송(PAID), 무료 배송(FREE), 조건부 무료 배송(CONDITIONAL)", example = "FREE")
            @RequestParam(required = false) String deliveryType,

            @Parameter(description = "[정렬]신상품순(newest), 가격 낮은순(priceAsc), 가격 높은순(priceDesc), 인기순(popular)")
            @RequestParam(defaultValue = "newest") String sort,

            @Parameter(description = "[페이징]조회할 페이지 번호 (1부터 시작)", example = "1")
            @RequestParam(defaultValue = "1") int page,

            @Parameter(description = "[페이징]한 페이지에 보여줄 상품 수", example = "10")
            @RequestParam(defaultValue = "10") int size
    ) {
        // 프론트는 1부터 시작 -> Spring Pageable은 0부터 시작하므로 -1 처리
        Pageable pageable = PageRequest.of(page - 1, size);

        ProductListResponse products = productService.getProducts(
                categoryId, tagIds, minPrice, maxPrice, deliveryType, sort, pageable
        );
        return ResponseEntity.ok(RsData.of("200", "상품 목록 조회 성공", products));
    }

    /** 상품 상세 조회 */
    @GetMapping("/{productUuid}")
    @Operation(
            summary = "상품 상세 조회",
            description = "상품 UUID를 기반으로 상세 정보를 조회합니다.",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "상품 상세 조회 성공",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            example = """
                                                {
                                                  "resultCode": "200",
                                                  "msg": "상품 상세 조회 성공",
                                                  "data": {
                                                    "productUuid": "550e8400-e29b-41d4-a716-446655440000",
                                                    "artistName": "김작가",
                                                    "brandName": "문구브랜드",
                                                    "name": "벚꽃 키링",
                                                    "averageRating": 4.6,
                                                    "reviewCount": 42,
                                                    "price": 10000,
                                                    "discountRate": 10,
                                                    "discountPrice": 9000,
                                                    "bundleShippingAvailable": true,
                                                    "deliveryCharge": 3000,
                                                    "deliveryType": "CONDITIONAL_FREE",
                                                    "conditionalFreeAmount": 30000,
                                                    "additionalShippingCharge": 5000,
                                                    "options": [
                                                      {"optionName": "색상-핑크", "optionStock": 50, "optionAdditionalPrice": 0},
                                                      {"optionName": "색상-화이트", "optionStock": 30, "optionAdditionalPrice": 500}
                                                    ],
                                                    "additionalProducts": [
                                                      {"name": "포장지 추가", "stock": 100, "price": 1000}
                                                    ],
                                                    "images": [
                                                      {"fileUrl": "https://s3.mori-mori.store/images/cherry_keyring_1.jpg", "fileType": "MAIN"},
                                                      {"fileUrl": "https://s3.mori-mori.store/images/cherry_keyring_2.jpg", "fileType": "ADDITIONAL"}
                                                    ],
                                                    "essentialInfo": {
                                                      "productModelName": "MM-CHERRY-24",
                                                      "certification": true,
                                                      "origin": "대한민국",
                                                      "material": "면 100%",
                                                      "size": "12x30x5cm",
                                                      "businessName": "김작가문구",
                                                      "businessNumber": "123-45-67890",
                                                      "ownerName": "김작가",
                                                      "asManager": "김작가/010-1234-5678",
                                                      "email": "kim@example.com",
                                                      "businessAddress": "서울특별시 강남구",
                                                      "telecomSalesNumber": "2023-서울강남-0001"
                                                    },
                                                    "stock": 100,
                                                    "description": "<p>벚꽃 키링 상세정보 및 사용 방법...</p>",
                                                    "minQuantity": 1,
                                                    "maxQuantity": 5,
                                                    "sellingStatus": "SELLING",
                                                    "displayStatus": "DISPLAYING",
                                                    "isPlanned": false,
                                                    "isRestock": true,
                                                    "tags": [
                                                      {"id": 1, "name": "벚꽃"},
                                                      {"id": 2, "name": "키링"},
                                                      {"id": 3, "name": "한정판"}
                                                    ],
                                                    "categoryId": 1
                                                  }
                                                }
                                                """
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "존재하지 않는 상품",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            example = """
                                                {
                                                  "resultCode": "404",
                                                  "msg": "존재하지 않는 상품입니다.",
                                                  "data": null
                                                }
                                                """
                                    )
                            )
                    )
            }
    )
    public ResponseEntity<RsData<ProductDetailResponse>> getProductDetail(
            @PathVariable UUID productUuid) {
        ProductDetailResponse response = productService.getProductDetail(productUuid);
        return ResponseEntity.ok(RsData.of("200", "상품 상세 조회 성공", response));
    }

    /** 상품 상세 - 작가 정보 조회 */
    @GetMapping("/{productUuid}/artist")
    @Operation(
            summary = "상품 상세 - 작가 정보 조회",
            description = "상품 UUID를 기반으로 해당 상품의 작가 정보를 조회합니다.",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "작가 정보 조회 성공",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            example = """
                                                {
                                                  "resultCode": "200",
                                                  "msg": "작가 정보 조회 성공",
                                                  "data": {
                                                    "artistName": "김작가",
                                                    "followerCount": 350,
                                                    "approvedDate": "2025.10.10",
                                                    "profileImageUrl": "https://bucket.s3.amazonaws.com/artist-profile/uuid.png",
                                                    "artistPageUrl": "https://www.mori-mori.store/forest/42",
                                                    "description": "작가 소개 텍스트..."
                                                  }
                                                }
                                                """
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "작가 프로필이 존재하지 않음",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            example = """
                                                {
                                                  "resultCode": "404",
                                                  "msg": "작가 프로필이 없습니다.",
                                                  "data": null
                                                }
                                                """
                                    )
                            )
                    )
            }
    )
    public ResponseEntity<RsData<ProductArtistInfoResponse>> getArtistInfoByProduct(
            @Parameter(description = "상품 UUID", example = "550e8400-e29b-41d4-a716-446655440000", required = true)
            @PathVariable UUID productUuid) {
        ProductArtistInfoResponse response = productService.getArtistInfoByProduct(productUuid);
        return ResponseEntity.ok(RsData.of("200", "작가 정보 조회 성공", response));
    }


    /** 상품 수정 */
    @PutMapping("/{productUuid}")
    @Operation(
            summary = "상품 수정",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "상품 수정 성공",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            example = """
                                                {
                                                  "resultCode": "200",
                                                  "msg": "상품이 성공적으로 수정되었습니다.",
                                                  "data": "4d9f5d8a-9f39-4f79-b18b-7a9b3adcb45c"
                                                }
                                                """
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "잘못된 요청 (Validation 실패, 존재하지 않는 카테고리/태그 등)",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            example = """
                                                {
                                                  "resultCode": "400",
                                                  "msg": "최대 구매 수량은 최소 구매 수량보다 작을 수 없습니다.",
                                                  "data": null
                                                }
                                                """
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "403",
                            description = "권한 없음",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            example = """
                                                {
                                                  "resultCode": "403",
                                                  "msg": "본인이 등록한 상품만 수정 가능합니다.",
                                                  "data": null
                                                }
                                                """
                                    )
                            )
                    )
            }
    )
    public ResponseEntity<RsData<UUID>> updateProduct(
            @PathVariable UUID productUuid,
            @Valid @RequestBody UpdateProductRequest request,
            @AuthenticationPrincipal CustomUserDetails customUserDetails) {
        UUID updatedProductUuid = productService.updateProduct(productUuid, request, customUserDetails);
        return ResponseEntity.ok(RsData.of("200", "상품이 성공적으로 수정되었습니다.", updatedProductUuid));
    }

    /** 상품 삭제(논리삭제) */
    @DeleteMapping("/{productUuid}")
    @Operation(
            summary = "상품 삭제",
            description = "관리자(ADMIN/ROOT) 또는 상품을 등록한 작가 본인만 삭제 가능하며, 논리삭제 처리됩니다.",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "상품 삭제 성공",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            example = """
                                                {
                                                  "resultCode": "200",
                                                  "msg": "상품이 삭제되었습니다.",
                                                  "data": "550e8400-e29b-41d4-a716-446655440000"
                                                }
                                                """
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "403",
                            description = "권한 없음",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            example = """
                                                {
                                                  "resultCode": "403",
                                                  "msg": "상품 삭제 권한이 없습니다.",
                                                  "data": null
                                                }
                                                """
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "상품 없음",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            example = """
                                                {
                                                  "resultCode": "404",
                                                  "msg": "존재하지 않는 상품입니다.",
                                                  "data": null
                                                }
                                                """
                                    )
                            )
                    )
            }
    )
    public ResponseEntity<RsData<UUID>> deleteProduct(
            @Parameter(description = "삭제할 상품 UUID", example = "550e8400-e29b-41d4-a716-446655440000", required = true)
            @PathVariable UUID productUuid,

            @Parameter(hidden = true)
            @AuthenticationPrincipal CustomUserDetails customUserDetails) {

        productService.deleteProduct(productUuid, customUserDetails);
        return ResponseEntity.ok(RsData.of("200", "상품이 삭제되었습니다.", productUuid));
    }


    /**
     * 상품 공유 링크 생성 (UTM 파라미터 포함)
     * 
     * 누구나 공유 가능 (로그인 불필요)
     * 2025.10.05 수정 - productId를 productUuid로 변경
     */
    @GetMapping("/{productUuid}/share-link")
    @Operation(
            summary = "상품 공유 링크 생성",
            description = "누구나 상품을 소셜 미디어에 공유할 수 있는 UTM 파라미터가 포함된 링크를 생성합니다.<br>" +
                    "로그인 없이도 사용 가능하며, 작가 ID는 상품 소유자 기준으로 자동 설정됩니다.<br><br>" +
                    "지원 플랫폼: instagram, youtube, naver, kakao, facebook, twitter, band, pinterest, tiktok, linkedin",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "공유 링크 생성 성공",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            example = """
                                                    {
                                                      "resultCode": "200",
                                                      "msg": "공유 링크가 생성되었습니다.",
                                                      "data": {
                                                        "shareLink": "https://mori-mori.store/product/550e8400-e29b-41d4-a716-446655440000?utm_source=instagram&utm_medium=social&utm_campaign=artist_42&utm_content=product_share",
                                                        "platform": "instagram",
                                                        "artistId": 42,
                                                        "productUuid": "550e8400-e29b-41d4-a716-446655440000",
                                                        "description": "멋진 도자기 작품"
                                                      }
                                                    }
                                                    """
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "잘못된 요청 (플랫폼 누락, 존재하지 않는 상품 등)",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            example = """
                                                    {
                                                      "resultCode": "400",
                                                      "msg": "플랫폼을 지정해야 합니다.",
                                                      "data": null
                                                    }
                                                    """
                                    )
                            )
                    )
            }
    )
    public ResponseEntity<RsData<ShareLinkResponse>> generateShareLink(
            @Parameter(description = "상품 UUID", example = "550e8400-e29b-41d4-a716-446655440000", required = true)
            @PathVariable UUID productUuid,
            
            @Parameter(description = "공유할 플랫폼 (instagram, youtube, naver, kakao 등)", example = "instagram", required = true)
            @RequestParam String platform,
            
            @Parameter(hidden = true)
            @AuthenticationPrincipal CustomUserDetails customUserDetails) {
        
        ShareLinkResponse response = productService.generateShareLink(productUuid, platform, customUserDetails);
        return ResponseEntity.ok(RsData.of("200", "공유 링크가 생성되었습니다.", response));
    }

}
