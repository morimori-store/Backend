package com.back.domain.product.product.service;

import com.back.domain.artist.dto.response.ArtistBusinessInfoResponse;
import com.back.domain.artist.entity.ArtistProfile;
import com.back.domain.artist.repository.ArtistProfileRepository;
import com.back.domain.artist.service.ArtistApplicationService;
import com.back.domain.product.category.entity.Category;
import com.back.domain.product.category.repository.CategoryRepository;
import com.back.domain.product.product.dto.request.BaseAdditionalProduct;
import com.back.domain.product.product.dto.request.BaseOption;
import com.back.domain.product.product.dto.request.CreateProductRequest;
import com.back.domain.product.product.dto.request.UpdateProductRequest;
import com.back.domain.product.product.dto.response.ProductArtistInfoResponse;
import com.back.domain.product.product.dto.response.ProductDetailResponse;
import com.back.domain.product.product.dto.response.ProductListResponse;
import com.back.domain.product.product.dto.response.ShareLinkResponse;
import com.back.domain.product.product.entity.*;
import com.back.domain.product.product.repository.ProductRepository;
import com.back.domain.product.product.repository.ProductTagMappingRepository;
import com.back.domain.product.tag.dto.response.TagResponse;
import com.back.domain.product.tag.entity.Tag;
import com.back.domain.product.tag.repository.TagRepository;
import com.back.domain.user.entity.Role;
import com.back.domain.user.entity.User;
import com.back.global.exception.ServiceException;
import com.back.global.s3.FileType;
import com.back.global.s3.S3FileRequest;
import com.back.global.s3.S3Service;
import com.back.global.s3.S3ValidationService;
import com.back.global.security.auth.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final TagRepository tagRepository;
    private final ProductTagMappingRepository productTagMappingRepository;
    private final S3ValidationService s3ValidationService;
    private final S3Service s3Service;
    private final ArtistApplicationService artistApplicationService;
    private final ArtistProfileRepository artistProfileRepository;


    @Value("${app.frontend-url:https://mori-mori.store}")
    private String frontendUrl;

    /** 상품 등록 */
    @Transactional
    public UUID createProduct(CreateProductRequest request, CustomUserDetails customUserDetails) {
        // 현재 로그인한 사용자
        User user = customUserDetails.getUser();
        // 존재하는 카테고리인지 검증
        Category category = validateAndGetCategory(request.categoryId());
        // 조건부 무료 배송비 & 최소/최대 구매수량 검증
        validateDeliveryAndQuantity(request.deliveryType(), request.conditionalFreeAmount(), request.minQuantity(), request.maxQuantity());

        // Product 생성
        Product product = buildProductFromRequest(request, user, category);

        // 옵션이 존재한다면 Option 생성
        product.getOptions().addAll(buildOptions(product, request.options()));
        // 추가상품이 존재한다면 AdditionalProduct 생성
        product.getAdditionalProducts().addAll(buildAdditionalProducts(product, request.additionalProducts()));
        // 태그 저장 (존재하는 태그인지 검증)
        product.getProductTags().addAll(buildProductTags(product, request.tags()));
        // 이미지 저장
        product.getImages().addAll(buildProductImages(product, request.images()));

        // cascade로 옵션, 추가상품, 태그, 이미지까지 같이 저장 후 uuid 반환
        return productRepository.save(product).getProductUuid();
    }

    /** 상품 목록 조회 */
    @Transactional(readOnly = true)
    public ProductListResponse getProducts(
            Long categoryId,
            List<Long> tagIds,
            Integer minPrice,
            Integer maxPrice,
            String deliveryType,
            String sort,
            Pageable pageable
    ) {
        // ProductCustomRepositoryImpl의 findProducts 호출 -> QueryDSL로 동적 쿼리 생성(필터링/정렬)+페이징+DTO반환
        return productRepository.findProducts(
                categoryId,
                tagIds,
                minPrice,
                maxPrice,
                deliveryType,
                sort,
                pageable
        );
    }

    /** 상품 상세 조회 */
    @Transactional
    public ProductDetailResponse getProductDetail(UUID productUuid) {
        // 존재하는 상품인지 검증
        Product product = getProductOrThrow(productUuid);
        // 작가 사업자 관련 정보 조회
        ArtistBusinessInfoResponse businessInfo = artistApplicationService.getBusinessInfo(product.getUser().getId());
        // 상품 + 작가 사업자 정보 DTO 반환
        return toProductDetailResponse(product, businessInfo);
    }

    /** 상품 상세 - 작가 정보 조회 */
    @Transactional(readOnly = true)
    public ProductArtistInfoResponse getArtistInfoByProduct(UUID productUuid) {
        Product product = getProductOrThrow(productUuid);
        ArtistProfile profile = artistProfileRepository.findByUserId(product.getUser().getId())
                .orElseThrow(() -> new ServiceException("404", "작가 프로필이 없습니다."));

        String approvedDate = profile.getArtistApplication().getCreateDate()
                .format(DateTimeFormatter.ofPattern("yyyy.MM.dd"));// 작가 승인일을 yyyy.MM.dd 형식의 문자열로 변환
        String artistPageUrl = frontendUrl + "/forest/" + profile.getId(); // 작가 페이지 url

        return new ProductArtistInfoResponse(
                profile.getArtistName(), 
                profile.getFollowerCount(),
                approvedDate,
                profile.getProfileImageUrl(),
                artistPageUrl,
                profile.getDescription()
        );
    }

    /** 메인페이지 - 신상품 조회 */
    @Transactional(readOnly = true)
    public List<ProductListResponse.ProductInfo> getAllNewProducts() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime fromDate = now.minusDays(14); // 최근 14일

        List<Product> allProducts = productRepository.findRecentProducts(fromDate, now);
        if (allProducts.isEmpty()) throw new ServiceException("404", "신상품이 존재하지 않습니다.");

        return allProducts.stream().map(this::toProductInfo).toList();
    }

    /** 메인페이지 - 할인중 상품 */
    @Transactional(readOnly = true)
    public List<ProductListResponse.ProductInfo> getOnSaleProducts() {
        List<Product> products = productRepository.findOnSaleProducts();
        if (products.isEmpty()) throw new ServiceException("404", "할인중인 상품이 존재하지 않습니다.");
        return products.stream().map(this::toProductInfo).toList();
    }

    /** 메인페이지 - 품절 임박 상품 */
    @Transactional(readOnly = true)
    public List<ProductListResponse.ProductInfo> getLowStockProducts() {
        List<Product> products = productRepository.findLowStockProducts();
        if (products.isEmpty()) throw new ServiceException("404", "품절 임박 상품이 존재하지 않습니다.");
        return products.stream().map(this::toProductInfo).toList();
    }

    /** 메인페이지 - 재입고 상품 */
    @Transactional(readOnly = true)
    public List<ProductListResponse.ProductInfo> getRestockProducts() {
        List<Product> products = productRepository.findRestockProducts();
        if (products.isEmpty()) throw new ServiceException("404", "재입고 상품이 존재하지 않습니다.");
        return products.stream().map(this::toProductInfo).toList();
    }

    /** 메인페이지 - 기획 상품 */
    @Transactional(readOnly = true)
    public List<ProductListResponse.ProductInfo> getPlannedProducts() {
        List<Product> products = productRepository.findPlannedProducts();
        if (products.isEmpty()) throw new ServiceException("404", "기획 상품이 존재하지 않습니다.");
        return products.stream().map(this::toProductInfo).toList();
    }

    /** 메인페이지 - 오픈 예정 상품 */
    @Transactional(readOnly = true)
    public List<ProductListResponse.ProductInfo> getUpcomingProducts() {
        LocalDateTime today = LocalDateTime.now();
        List<Product> products = productRepository.findUpcomingProducts(today);
        if (products.isEmpty()) throw new ServiceException("404", "오픈 예정 상품이 존재하지 않습니다.");
        return products.stream().map(this::toProductInfo).toList();
    }

    /** 상품 수정 */
    @Transactional
    public UUID updateProduct(UUID productUuid, UpdateProductRequest request, CustomUserDetails customUserDetails) {
        // 존재하는 상품인지 검증
        Product product = getProductOrThrow(productUuid);
        // 상품 수정 권한 검증 (본인 상품만 수정 가능)
        checkProductOwner(product, customUserDetails.getUser());
        // 존재하는 카테고리인지 검증
        Category category = validateAndGetCategory(request.categoryId());
        // 조건부 무료 배송비 & 최소/최대 구매수량 검증
        validateDeliveryAndQuantity(request.deliveryType(), request.conditionalFreeAmount(), request.minQuantity(), request.maxQuantity());

        // Product 수정
        updateProductFromRequest(product, request, category);

        // 옵션 처리
        product.getOptions().clear();
        product.getOptions().addAll(buildOptions(product, request.options()));
        // 추가상품 처리
        product.getAdditionalProducts().clear();
        product.getAdditionalProducts().addAll(buildAdditionalProducts(product, request.additionalProducts()));
        // 태그 처리
        // 기존 태그 매핑 삭제
        productTagMappingRepository.deleteAllByProductId(product.getId()); // 제품 ID로 모든 태그 매핑 삭제
        product.getProductTags().clear();
        product.getProductTags().addAll(buildProductTags(product, request.tags()));
        // 이미지 처리
        updateProductImages(product, request.images());

        // cascade로 옵션, 추가상품, 태그, 이미지까지 같이 저장 후 uuid 반환
        return productRepository.save(product).getProductUuid();
    }

    /** 상품 삭제(논리삭제) */
    @Transactional
    public void deleteProduct(UUID productUuid, CustomUserDetails customUserDetails) {
        // 존재하는 상품인지 검증
        Product product = getProductOrThrow(productUuid);
        // 현재 로그인한 사용자
        User user = customUserDetails.getUser();
        // 삭제 권한 검증 (관리자이거나, 상품 등록한 작가 본인만 삭제 가능)
        if (!user.getRole().equals(Role.ADMIN) && !user.getRole().equals(Role.ROOT) && !product.getUser().equals(user)) {
            throw new ServiceException("403", "상품 삭제 권한이 없습니다.");
        }

        // 논리삭제 처리
        product.setDeleted(true);
        product.setDisplayStatus(DisplayStatus.END_OF_DISPLAY); // 전시 상태 전시 종료로 변경 (-> 사용자에게 안보임)
        product.setSellingStatus(SellingStatus.END_OF_SALE); // 판매 상태 판매 종료로 변경
        // DB 저장
        productRepository.save(product);
    }

    /** 상품 이미지(파일) 다운로드 */
    @Transactional(readOnly = true)
    public ProductImage getProductDocument(UUID productUuid) {
        // productUuid로 상품 조회
        Product product = productRepository.findByProductUuid(productUuid)
                .orElseThrow(() -> new ServiceException("404", "존재하지 않는 상품입니다. productUuid: " + productUuid));
        return product.getImages().stream()
                .filter(img -> img.getFileType() == FileType.DOCUMENT)
                .findFirst()
                .orElseThrow(() -> new ServiceException("404", "다운로드할 문서가 존재하지 않습니다."));
    }


    /**
     * 상품 공유 링크 생성 (UTM 파라미터 포함)
     * 
     * 누구나 상품을 공유할 수 있으며, 작가 ID는 상품 소유자 기준으로 설정됨
     * UTM 파라미터를 통해 작가별 유입 경로 추적 가능
     * 
     * @param productUuid 상품 UUID
     * @param platform 공유할 플랫폼 (instagram, youtube, naver 등)
     * @param customUserDetails 현재 로그인한 사용자 정보 (선택사항)
     * @return UTM 파라미터가 포함된 공유 링크
     */
    @Transactional(readOnly = true)
    public ShareLinkResponse generateShareLink(UUID productUuid, String platform, CustomUserDetails customUserDetails) {
        log.info("공유 링크 생성 시작 - 상품 UUID: {}, 플랫폼: {}", productUuid, platform);

        // 상품 존재 여부 확인
        Product product = productRepository.findByProductUuid(productUuid)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 상품입니다. UUID: " + productUuid));

        // 논리 삭제된 상품은 공유 불가
        if (product.isDeleted()) {
            throw new IllegalArgumentException("삭제된 상품은 공유할 수 없습니다.");
        }

        // 판매 중이 아닌 상품은 공유 불가 (선택적)
        if (product.getSellingStatus() != SellingStatus.SELLING) {
            log.warn("판매 중이 아닌 상품 공유 시도 - productUuid: {}, status: {}", 
                     productUuid, product.getSellingStatus());
            // 경고만 하고 공유는 허용 (작가가 예약 판매 등을 미리 공유할 수 있도록)
        }

        // 상품 소유자(작가)의 ID 사용
        Long artistId = product.getUser().getId();

        // 플랫폼 검증 및 정규화
        String normalizedPlatform = validateAndNormalizePlatform(platform);

        // 베이스 URL 생성 (프론트엔드 URL)
        String baseUrl = frontendUrl + "/product/" + productUuid;

        String utmParams = String.format(
                "?utm_source=%s&utm_medium=social&utm_campaign=artist_%d&utm_content=product_share",
                normalizedPlatform,
                artistId
        );

        String shareLink = baseUrl + utmParams;

        log.info("공유 링크 생성 완료 - 작가 ID: {}, 상품 UUID: {}, 플랫폼: {}", 
                 artistId, productUuid, normalizedPlatform);

        return new ShareLinkResponse(
                shareLink,
                normalizedPlatform,
                artistId,
                productUuid,
                product.getName()
        );
    }

    /**
     * 플랫폼 검증 및 정규화
     * 
     * @param platform 플랫폼명
     * @return 정규화된 플랫폼명 (소문자)
     */
    private String validateAndNormalizePlatform(String platform) {
        if (platform == null || platform.isBlank()) {
            throw new IllegalArgumentException("플랫폼을 지정해야 합니다.");
        }

        // 소문자로 변환
        String normalized = platform.toLowerCase().trim();

        // 지원하는 플랫폼 목록
        Set<String> supportedPlatforms = Set.of(
                "instagram", "youtube", "naver", "kakao", "facebook", 
                "twitter", "band", "pinterest", "tiktok", "linkedin"
        );

        if (!supportedPlatforms.contains(normalized)) {
            log.warn("지원하지 않는 플랫폼이지만 허용: {}", normalized);
            // 지원하지 않는 플랫폼도 일단 허용 (GA4에서 추적은 됨)
        }

        return normalized;
    }



    /** --------메서드 추상화-------- */

    /** Validation 메서드 */
    // 카테고리 검증
    private Category validateAndGetCategory(Long categoryId) {
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ServiceException("400", "존재하지 않는 카테고리입니다."));
    }
    // 조건부 무료 배송비 & 최소/최대 구매수량 검증
    private void validateDeliveryAndQuantity(String deliveryTypeStr, Integer conditionalFreeAmount, int minQuantity, int maxQuantity) {
        if (deliveryTypeStr == null) {
            throw new ServiceException("400", "배송 타입은 필수입니다.");
        }
        DeliveryType deliveryType;
        try {
            deliveryType = DeliveryType.valueOf(deliveryTypeStr);
        } catch (IllegalArgumentException e) {
            throw new ServiceException("400", "잘못된 배송 타입입니다: " + deliveryTypeStr);
        }
        if (deliveryType == DeliveryType.CONDITIONAL_FREE && (conditionalFreeAmount == null || conditionalFreeAmount < 1)) {
            throw new ServiceException("400", "조건부 무료 기준 금액은 NULL일 수 없으며, 1 이상이어야 합니다.");
        }
        if (maxQuantity < minQuantity) {
            throw new ServiceException("400", "최대 구매 수량은 최소 구매 수량보다 작을 수 없습니다.");
        }
    }
    // 상품 수정 권한 검증
    private void checkProductOwner(Product product, User user) {
        if (!product.getUser().getId().equals(user.getId())) {
            throw new ServiceException("403", "본인이 등록한 상품만 수정 가능합니다.");
        }
    }
    // 존재하지 않는 상품 검증
    public Product getProductOrThrow(UUID productUuid) {
        return productRepository.findByProductUuid(productUuid)
                .orElseThrow(() -> new ServiceException("404", "존재하지 않는 상품입니다. UUID: " + productUuid));
    }


    /** DTO -> 엔티티 */
    // Product 생성
    private Product buildProductFromRequest(CreateProductRequest request, User user, Category category) {
        return Product.builder()
                .category(category) // DTO에 있던 검증된 카테고리
                .user(user) // 작가 정보
                .name(request.name()) // 상품명
                .brandName(request.brandName()) // 브랜드명
                .price(request.price()) // 가격
                .discountRate(request.discountRate()) // 할인율
                .bundleShippingAvailable(request.bundleShippingAvailable()) // 묶음 배송 가능 여부
                .deliveryCharge(request.deliveryCharge()) // 기본 배송비
                .additionalShippingCharge(request.additionalShippingCharge()) // 제주 추가 배송비
                .deliveryType(DeliveryType.valueOf(request.deliveryType())) // 배송비 유형
                .conditionalFreeAmount(request.conditionalFreeAmount()) //조건부 배송일 경우 무료배송 조건 금액
                .stock(request.stock()) //재고
                .description(request.description()) // 상품 정보(텍스트+이미지 태그로 이루어진 HTML?)
                .sellingStatus(request.sellingStatus() != null ? SellingStatus.valueOf(request.sellingStatus()) : SellingStatus.SELLING) // 판매 상태 (null이면 기본값은 판매중)
                .displayStatus(request.displayStatus() != null ? DisplayStatus.valueOf(request.displayStatus()) : DisplayStatus.DISPLAYING) // 전시 상태 (null이면 기본값은 전시중)
                .minQuantity(request.minQuantity()) // 최소 구매수량
                .maxQuantity(request.maxQuantity()) // 최대 구매수량
                .isPlanned(request.isPlanned()) // 기획상품 여부
                .isRestock(request.isRestock()) // 재입고상품 여부
                .sellingStartDate(request.sellingStartDate() != null ? request.sellingStartDate() : null)// 판매시작일
                .sellingEndDate(request.sellingEndDate() != null ? request.sellingEndDate() : null)// 판매종료일
                .productModelName(request.productModelName()) //품명
                .certification(request.certification()) // 법에 의한 인증,허가 확인사항
                .origin(request.origin()) // 제조국
                .material(request.material()) // 재질
                .size(request.size()) //사이즈
                .isDeleted(false) // 논리삭제 여부(db에서 삭제는 안하고, 삭제처리만 된 것)
                .build();
    }

    // Product 수정
    private void updateProductFromRequest(Product product, UpdateProductRequest request, Category category){
        // 이전 재고 저장
        int previousStock = product.getStock();

        // DTO 값 그대로 덮어쓰기
        product.setCategory(category);
        product.setName(request.name());
        product.setBrandName(request.brandName());
        product.setPrice(request.price());
        product.setDiscountRate(request.discountRate());
        product.setBundleShippingAvailable(request.bundleShippingAvailable());
        product.setDeliveryCharge(request.deliveryCharge());
        product.setAdditionalShippingCharge(request.additionalShippingCharge());
        product.setDeliveryType(DeliveryType.valueOf(request.deliveryType()));
        product.setConditionalFreeAmount(request.conditionalFreeAmount());
        product.setStock(request.stock()); // 변경된 재고
        product.setDescription(request.description());
        product.setSellingStatus(request.sellingStatus() != null ? SellingStatus.valueOf(request.sellingStatus()) : SellingStatus.SELLING); // null이면 기본값 판매중
        product.setDisplayStatus(request.displayStatus() != null ? DisplayStatus.valueOf(request.displayStatus()) : DisplayStatus.DISPLAYING); // null이면 기본값 전시중
        product.setMinQuantity(request.minQuantity());
        product.setMaxQuantity(request.maxQuantity());
        product.setProductModelName(request.productModelName());
        product.setCertification(request.certification());
        product.setOrigin(request.origin());
        product.setMaterial(request.material());
        product.setSize(request.size());
        product.setPlanned(request.isPlanned());
        product.setRestock(request.isRestock());
        product.setSellingStartDate(request.sellingStartDate());
        product.setSellingEndDate(request.sellingEndDate());

        // 재고가 0 -> 0보다 큰 값으로 변경되면 재입고 상품 여부(isRestock) 자동 true
        if (previousStock == 0 && product.getStock() > 0) {
            product.setRestock(true);
        }
    }

    /** 엔티티 -> DTO  */
    // 상품 상세 조회
    private ProductDetailResponse toProductDetailResponse(Product product, ArtistBusinessInfoResponse businessInfo) {
        // 상품 필수 정보 DTO 생성
        ProductDetailResponse.ProductEssentialInfo essentialInfo =
                new ProductDetailResponse.ProductEssentialInfo(
                        product.getProductModelName(),
                        product.isCertification(),
                        product.getOrigin(),
                        product.getMaterial(),
                        product.getSize(),
                        businessInfo.businessName(),
                        businessInfo.businessNumber(),
                        businessInfo.ownerName(),
                        businessInfo.asManager(),
                        businessInfo.email(),
                        businessInfo.businessAddress(),
                        businessInfo.telecomSalesNumber()
                );

        return new ProductDetailResponse(
                product.getProductUuid(),
                product.getUser().getName(),
                product.getBrandName(),
                product.getName(),
                product.getAverageRating() != null ? product.getAverageRating() : 0.0,
                product.getReviewCount() != null ? product.getReviewCount() : 0,
                product.getPrice(),
                product.getDiscountRate(),
                product.getDiscountPrice(),
                product.isBundleShippingAvailable(),
                product.getDeliveryCharge(),
                product.getDeliveryType().name(),
                product.getConditionalFreeAmount(),
                product.getAdditionalShippingCharge(),
                mapOptions(product.getOptions()),
                mapAdditionalProducts(product.getAdditionalProducts()),
                mapImages(product.getImages()),
                essentialInfo,
                product.getStock(),
                product.getDescription(),
                product.getMinQuantity(),
                product.getMaxQuantity(),
                product.getSellingStatus().name(),
                product.getDisplayStatus().name(),
                product.isPlanned(),
                product.isRestock(),
                mapTags(product.getProductTags()),
                product.getSellingStartDate(),
                product.getSellingEndDate(),
                product.getCategory().getId()
        );
    }

    // 메인페이지 주제별 상품 목록 조회를 위해 Product엔티티 → ProductInfo DTO
    private ProductListResponse.ProductInfo toProductInfo(Product product) {
        return new ProductListResponse.ProductInfo(
                product.getProductUuid(),
                product.getImages().stream()
                        .filter(img -> img.getFileType() == FileType.THUMBNAIL)
                        .findFirst()
                        .map(ProductImage::getFileUrl)
                        .orElse(""),
                product.getBrandName(),
                product.getName(),
                product.getPrice(),
                product.getDiscountRate(),
                product.getDiscountPrice(),
                product.getAverageRating()
        );
    }



    /** 옵션, 추가상품, 태그, 이미지 메서드  */
    // 옵션이 존재한다면 Option 생성
    private List<Option> buildOptions(Product product, List<? extends BaseOption> options) {
        if (options == null || options.isEmpty()) return List.of();
        return options.stream()
                .map(o -> Option.builder()
                        .product(product)
                        .optionName(o.optionName())
                        .optionStock(o.optionStock())
                        .optionAdditionalPrice(o.optionAdditionalPrice())
                        .build())
                .toList();
    }
    // 추가상품이 존재한다면 AdditionalProduct 생성
    private List<AdditionalProduct> buildAdditionalProducts(Product product, List<? extends BaseAdditionalProduct> additionalProducts) {
        if (additionalProducts == null || additionalProducts.isEmpty()) return List.of();
        return additionalProducts.stream()
                .map(a -> AdditionalProduct.builder()
                        .product(product)
                        .additionalName(a.additionalProductName())
                        .additionalStock(a.additionalProductStock())
                        .additionalPrice(a.additionalProductPrice())
                        .build())
                .toList();
    }
    // 태그 저장
    private List<ProductTagMapping> buildProductTags(Product product, List<Long> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) return List.of();
        return tagIds.stream()
                .map(tagId -> {
                    Tag tag = tagRepository.findById(tagId)
                            .orElseThrow(() -> new ServiceException("400", "존재하지 않는 태그입니다. tagId: " + tagId));
                    return ProductTagMapping.builder()
                            .product(product)
                            .tag(tag)
                            .build();
                }).toList();
    }
    // 이미지 저장 (Create용)
    private List<ProductImage> buildProductImages(Product product, List<S3FileRequest> images) {
        if (images == null || images.isEmpty()) return List.of();
        return images.stream()
                .map(img -> {
                    s3ValidationService.validateFileExists(img.s3Key());
                    return ProductImage.builder()
                            .product(product)
                            .fileUrl(img.url())
                            .fileType(img.type())
                            .s3Key(img.s3Key())
                            .originalFilename(img.originalFileName())
                            .build();
                }).toList();
    }
    // 이미지 저장 (Update용)
    private void updateProductImages(Product product, List<S3FileRequest> incomingImages) {
        if (incomingImages == null) incomingImages = List.of();
        // 기존 이미지
        Map<String, ProductImage> existingMap = product.getImages().stream()
                .collect(Collectors.toMap(ProductImage::getS3Key, img -> img));
        // 프론트에게 받은 이미지 S3Key 리스트
        Set<String> incomingKeys = incomingImages.stream().map(S3FileRequest::s3Key).collect(Collectors.toSet());

        // DB, S3에서 이미지 삭제 (DB에 있고 프론트에 없는 이미지)
        product.getImages().removeIf(img -> {
            if (!incomingKeys.contains(img.getS3Key())) {
                s3Service.deleteFile(img.getS3Key());
                return true;
            }
            return false;
        });

        // 이미지 추가 (프론트에 있는데 DB에는 없는 이미지)
        for (S3FileRequest img : incomingImages) {
            if (!existingMap.containsKey(img.s3Key())) {
                s3ValidationService.validateFileExists(img.s3Key());
                product.getImages().add(ProductImage.builder()
                        .product(product)
                        .fileUrl(img.url())
                        .fileType(img.type())
                        .s3Key(img.s3Key())
                        .originalFilename(img.originalFileName())
                        .build());
            }
        }
    }

    // 상품 상세 조회에서 옵션 엔티티를 DTO로 변환
    private List<ProductDetailResponse.OptionResponse> mapOptions(List<Option> options) {
        if (options == null || options.isEmpty()) return List.of();
        return options.stream()
                .map(ProductDetailResponse.OptionResponse::fromEntity)
                .collect(Collectors.toList());
    }

    // 상품 상세 조회에서 추가상품 엔티티를 DTO로 변환
    private List<ProductDetailResponse.AdditionalProductResponse> mapAdditionalProducts(List<AdditionalProduct> additionalProducts) {
        if (additionalProducts == null || additionalProducts.isEmpty()) return List.of();
        return additionalProducts.stream()
                .map(ProductDetailResponse.AdditionalProductResponse::fromEntity)
                .collect(Collectors.toList());
    }

    // 상품 상세 조회에서 상품 이미지 엔티티를 DTO로 변환
    private List<ProductDetailResponse.ProductImageResponse> mapImages(List<ProductImage> images) {
        if (images == null || images.isEmpty()) return List.of();
        return images.stream()
                .map(ProductDetailResponse.ProductImageResponse::fromEntity)
                .collect(Collectors.toList());
    }

    // 상품 상세 조회에서 태그 엔티티를 DTO로 변환
    private List<TagResponse> mapTags(Collection<ProductTagMapping> productTags) {
        if (productTags == null || productTags.isEmpty()) return List.of();
        return productTags.stream()
                .map(pt -> new TagResponse(pt.getTag().getId(), pt.getTag().getName()))
                .collect(Collectors.toList());
    }

}
