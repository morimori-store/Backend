package com.back.domain.dashboard.artist.controller;

import com.back.domain.dashboard.artist.dto.request.*;
import com.back.domain.dashboard.artist.dto.response.*;
import com.back.domain.dashboard.artist.service.ArtistDashboardService;
import com.back.domain.user.entity.Role;
import com.back.domain.user.entity.User;
import com.back.global.rsData.RsData;
import com.back.global.security.auth.CustomUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * ArtistDashboardController 테스트
 * 2025.09.30 펀딩 실제 DB 연동에 맞춰 테스트 수정
 * 2025.10.02 JWT 표준 패턴 적용에 따른 테스트 수정
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("작가 대시보드 컨트롤러 테스트")
class ArtistDashboardControllerTest {

    @Mock
    private ArtistDashboardService artistDashboardService;

    @InjectMocks
    private ArtistDashboardController artistDashboardController;

    private static final Long TEST_ARTIST_ID = 5L;
    private CustomUserDetails testUserDetails;

    // 공통 상수들
    private static final String SUCCESS_CODE = "200";
    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 20;

    @BeforeEach
    void setUp() {
        // CustomUserDetails Mock 생성
        User mockUser = mock(User.class);
        given(mockUser.getId()).willReturn(TEST_ARTIST_ID);
        testUserDetails = new CustomUserDetails(mockUser, Role.ARTIST);
    }

    /**
     * 성공 응답 검증 및 데이터 추출 헬퍼 메서드
     */
    private <T> T assertSuccessResponse(ResponseEntity<RsData<T>> response, String expectedMessage) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();

        RsData<T> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.resultCode()).isEqualTo(SUCCESS_CODE);
        assertThat(body.msg()).isEqualTo(expectedMessage);

        T data = body.data();
        assertThat(data).isNotNull();
        return data;
    }

    // Mock 데이터 생성 헬퍼 메서드들
    private ArtistCashResponse.Balance createMockBalance() {
        return new ArtistCashResponse.Balance(
                72000,
                15000,
                0,
                72000,
                "KRW",
                LocalDateTime.of(2025, 9, 24, 10, 0)
        );
    }

    private ArtistProductResponse.Product createMockProduct() {
        return new ArtistProductResponse.Product(
                UUID.randomUUID(),
                "상품명입니다 상품명입니다",
                90000,
                10,
                81000,
                "SELLING",
                "판매중",
                "2025. 09. 18"
        );
    }

    private ArtistCashHistoryResponse.Transaction createMockTransaction(String type, String txId, int amount) {
        return new ArtistCashHistoryResponse.Transaction(
                txId,
                "2025-09-24T09:12:00+09:00",
                type,
                type.equals("DEPOSIT") ? "정산금 입금" : "모리캐시 환전",
                type.equals("DEPOSIT") ? amount : 0,
                type.equals("WITHDRAWAL") ? amount : 0,
                amount,
                type.equals("DEPOSIT") ? "WALLET" : "BANK_TRANSFER",
                type.equals("DEPOSIT") ? "모리캐시" : "계좌이체",
                "COMPLETED",
                type.equals("WITHDRAWAL") ? "우리은행 ****-**-***** 홍길동" : null
        );
    }

    private ArtistOrderResponse.Order createMockOrder(String orderId, String orderNumber, String status) {
        return new ArtistOrderResponse.Order(
                orderId,
                orderNumber,
                "2025-09-18",
                status,
                status.equals("PENDING") ? "발주 전" : "배송 중",
                47500,
                "상품명입니다 상품명입니다",
                2,
                new ArtistOrderResponse.Buyer(201L, "heroeson02", "손경호"),
                new ArtistOrderResponse.Shipment("READY", null, null),
                new ArtistOrderResponse.Permissions(true, true)
        );
    }

    @Test
    @DisplayName("작가 지갑 잔액 조회 성공")
    void getCashBalance_Success() {
        // Given
        ArtistCashResponse.Balance mockBalance = createMockBalance();
        given(artistDashboardService.getCashBalance(eq(TEST_ARTIST_ID)))
                .willReturn(mockBalance);

        // When
        ResponseEntity<RsData<ArtistCashResponse.Balance>> response =
                artistDashboardController.getCashBalance(testUserDetails);

        // Then
        ArtistCashResponse.Balance data = assertSuccessResponse(response, "작가지갑 요약 조회 성공");
        assertAll(
                () -> assertThat(data.currentBalance()).isEqualTo(72000),
                () -> assertThat(data.pendingSettlement()).isEqualTo(15000),
                () -> assertThat(data.pendingWithdrawal()).isEqualTo(0),
                () -> assertThat(data.withdrawable()).isEqualTo(72000),
                () -> assertThat(data.currency()).isEqualTo("KRW")
        );
    }

    @Test
    @DisplayName("작가 상품 목록 조회 성공")
    void getProducts_Success() {
        // Given
        List<ArtistProductResponse.Product> content = List.of(createMockProduct());
        ArtistProductResponse.List mockResponse = new ArtistProductResponse.List(
                content, DEFAULT_PAGE, 10, 28, 3, true, false);

        given(artistDashboardService.getProducts(eq(TEST_ARTIST_ID), any(ArtistProductSearchRequest.class)))
                .willReturn(mockResponse);

        ArtistProductSearchRequest request = new ArtistProductSearchRequest(
                DEFAULT_PAGE, 10, null, null, "createDate", "DESC");

        // When
        ResponseEntity<RsData<ArtistProductResponse.List>> response =
                artistDashboardController.getProducts(testUserDetails, request);

        // Then
        ArtistProductResponse.List data = assertSuccessResponse(response, "내 상품 목록 조회 성공");
        ArtistProductResponse.Product firstProduct = data.getContent().getFirst();

        assertAll(
                () -> assertThat(data.getContent()).hasSize(1),
                () -> assertThat(firstProduct.productUuid()).isNotNull(),
                () -> assertThat(firstProduct.productName()).isNotBlank(),
                () -> assertThat(firstProduct.price()).isPositive(),
                () -> assertThat(firstProduct.discountRate()).isNotNegative(),
                () -> assertThat(firstProduct.discountPrice()).isPositive(),
                () -> assertThat(firstProduct.sellingStatus()).isNotBlank(),
                () -> assertThat(data.getPage()).isEqualTo(DEFAULT_PAGE),
                () -> assertThat(data.getSize()).isEqualTo(10),
                () -> assertThat(data.getTotalElements()).isPositive()
        );
    }

    @Test
    @DisplayName("작가 캐시 내역 조회 성공")
    void getCashHistory_Success() {
        // Given
        ArtistCashHistoryResponse.Summary summary = new ArtistCashHistoryResponse.Summary(74000, 64000, 10000);

        List<ArtistCashHistoryResponse.Transaction> content = List.of(
                createMockTransaction("DEPOSIT", "TX-20250924-0001", 10000),
                createMockTransaction("WITHDRAWAL", "TX-20250923-0004", 64000)
        );

        ArtistCashHistoryResponse.List mockResponse = new ArtistCashHistoryResponse.List(
                summary, content, DEFAULT_PAGE, DEFAULT_SIZE, 2, 1, false, false
        );

        given(artistDashboardService.getCashHistory(eq(TEST_ARTIST_ID), any(ArtistCashHistorySearchRequest.class)))
                .willReturn(mockResponse);

        ArtistCashHistorySearchRequest request = new ArtistCashHistorySearchRequest(
                DEFAULT_PAGE, DEFAULT_SIZE, null, null, null, null, "transactedAt", "DESC");

        // When
        ResponseEntity<RsData<ArtistCashHistoryResponse.List>> response =
                artistDashboardController.getCashHistory(testUserDetails, request);

        // Then
        ArtistCashHistoryResponse.List data = assertSuccessResponse(response, "입금/환전 내역 조회 성공");
        ArtistCashHistoryResponse.Transaction firstTransaction = data.content().getFirst();

        assertAll(
                () -> assertThat(data.summary()).isNotNull(),
                () -> assertThat(data.summary().periodDepositTotal()).isNotNegative(),
                () -> assertThat(data.summary().periodWithdrawalTotal()).isNotNegative(),
                () -> assertThat(data.content()).hasSize(2),
                () -> assertThat(firstTransaction.txId()).isNotBlank(),
                () -> assertThat(firstTransaction.type()).isNotBlank(),
                () -> assertThat(firstTransaction.balanceAfter()).isNotNegative(),
                () -> assertThat(data.page()).isEqualTo(DEFAULT_PAGE),
                () -> assertThat(data.size()).isEqualTo(DEFAULT_SIZE),
                () -> assertThat(data.totalElements()).isPositive()
        );
    }

    @Test
    @DisplayName("작가 캐시 내역 조회 - 필터 파라미터 포함")
    void getCashHistory_WithFilters() {
        // Given
        ArtistCashHistoryResponse.List mockResponse = new ArtistCashHistoryResponse.List(
                new ArtistCashHistoryResponse.Summary(10000, 0, 10000),
                List.of(createMockTransaction("DEPOSIT", "TX-20250924-0001", 10000)),
                DEFAULT_PAGE, DEFAULT_SIZE, 1, 1, false, false
        );

        ArtistCashHistorySearchRequest request = new ArtistCashHistorySearchRequest(
                DEFAULT_PAGE, DEFAULT_SIZE, "DEPOSIT", "COMPLETED",
                "2025-09-01", "2025-09-30", "transactedAt", "DESC");

        given(artistDashboardService.getCashHistory(eq(TEST_ARTIST_ID), eq(request)))
                .willReturn(mockResponse);

        // When
        ResponseEntity<RsData<ArtistCashHistoryResponse.List>> response =
                artistDashboardController.getCashHistory(testUserDetails, request);

        // Then
        ArtistCashHistoryResponse.List data = assertSuccessResponse(response, "입금/환전 내역 조회 성공");
        assertAll(
                () -> assertThat(data.content()).hasSize(1),
                () -> assertThat(data.content().getFirst().type()).isEqualTo("DEPOSIT"),
                () -> assertThat(data.summary().periodWithdrawalTotal()).isEqualTo(0)
        );
    }

    @Test
    @DisplayName("작가 주문 내역 조회 성공")
    void getOrders_Success() {
        // Given
        ArtistOrderResponse.Summary summary = new ArtistOrderResponse.Summary(156, 8, 12, 142, 136, 5);

        List<ArtistOrderResponse.Order> content = List.of(
                createMockOrder("550e84...000", "0123157", "PENDING"),
                createMockOrder("550e84...001", "0123156", "SHIPPED")
        );

        ArtistOrderResponse.List mockResponse = new ArtistOrderResponse.List(
                summary, content, DEFAULT_PAGE, DEFAULT_SIZE, 156, 8, true, false
        );

        given(artistDashboardService.getOrders(eq(TEST_ARTIST_ID), any(ArtistOrderSearchRequest.class)))
                .willReturn(mockResponse);

        ArtistOrderSearchRequest request = new ArtistOrderSearchRequest(
                DEFAULT_PAGE, DEFAULT_SIZE, null, null, null, null, "orderDate", "DESC");

        // When
        ResponseEntity<RsData<ArtistOrderResponse.List>> response =
                artistDashboardController.getOrders(testUserDetails, request);

        // Then
        ArtistOrderResponse.List data = assertSuccessResponse(response, "주문 목록 조회 성공");
        ArtistOrderResponse.Order firstOrder = data.content().getFirst();

        assertAll(
                () -> assertThat(data.summary()).isNotNull(),
                () -> assertThat(data.summary().total()).isPositive(),
                () -> assertThat(data.summary().pending()).isNotNegative(),
                () -> assertThat(data.content()).hasSize(2),
                () -> assertThat(firstOrder.orderNumber()).isNotBlank(),
                () -> assertThat(firstOrder.status()).isNotBlank(),
                () -> assertThat(firstOrder.totalAmount()).isPositive(),
                () -> assertThat(firstOrder.buyer()).isNotNull(),
                () -> assertThat(firstOrder.permissions()).isNotNull(),
                () -> assertThat(data.page()).isEqualTo(DEFAULT_PAGE),
                () -> assertThat(data.size()).isEqualTo(DEFAULT_SIZE),
                () -> assertThat(data.totalElements()).isPositive(),
                () -> assertThat(data.hasNext()).isTrue()
        );
    }

    @Test
    @DisplayName("작가 취소 요청 목록 조회 성공")
    void getCancellationRequests_Success() {
        // Given
        ArtistCancellationResponse.CancellationRequest mockRequest = new ArtistCancellationResponse.CancellationRequest(
                1L, "ORD-20241225-001", "0123157", "CANCEL", "PENDING", "처리대기",
                "2024-12-26T09:00:00+09:00", "단순 변심", "취소 요청합니다.",
                new ArtistCancellationResponse.Customer(201L, "고객A"),
                new ArtistCancellationResponse.OrderItem(101L, "귀여운 스티커", 1, 15000),
                15000,
                new ArtistCancellationResponse.Permissions(true, true)
        );

        ArtistCancellationResponse.List mockResponse = new ArtistCancellationResponse.List(
                new ArtistCancellationResponse.Summary(8, 5, 2, 1),
                List.of(mockRequest),
                DEFAULT_PAGE, DEFAULT_SIZE, 8, 1, false, false
        );

        given(artistDashboardService.getCancellationRequests(eq(TEST_ARTIST_ID), any(ArtistCancellationSearchRequest.class)))
                .willReturn(mockResponse);

        ArtistCancellationSearchRequest request = new ArtistCancellationSearchRequest(
                DEFAULT_PAGE, DEFAULT_SIZE, null, null, null, null, null, "requestDate", "DESC");

        // When
        ResponseEntity<RsData<ArtistCancellationResponse.List>> response =
                artistDashboardController.getCancellationRequests(testUserDetails, request);

        // Then
        ArtistCancellationResponse.List data = assertSuccessResponse(response, "취소 요청 목록 조회 성공");
        assertAll(
                () -> assertThat(data.summary().total()).isEqualTo(8),
                () -> assertThat(data.content()).hasSize(1),
                () -> assertThat(data.content().getFirst().requestId()).isEqualTo(1L),
                () -> assertThat(data.content().getFirst().status()).isEqualTo("PENDING")
        );
    }

    @Test
    @DisplayName("작가 교환 요청 목록 조회 성공")
    void getExchangeRequests_Success() {
        // Given
        ArtistExchangeResponse.ExchangeRequest mockRequest = new ArtistExchangeResponse.ExchangeRequest(
                21L, null, "ORD-20241226-003", null, "PENDING", null, null, null, null, null, null, null, null
        );

        ArtistExchangeResponse.List mockResponse = new ArtistExchangeResponse.List(
                new ArtistExchangeResponse.Summary(5, 3, 0, 0),
                List.of(mockRequest),
                DEFAULT_PAGE, DEFAULT_SIZE, 5, 1, false, false
        );

        given(artistDashboardService.getExchangeRequests(eq(TEST_ARTIST_ID), any(ArtistExchangeSearchRequest.class)))
                .willReturn(mockResponse);

        ArtistExchangeSearchRequest request = new ArtistExchangeSearchRequest(
                DEFAULT_PAGE, DEFAULT_SIZE, null, null, null, null, null, "requestDate", "DESC");

        // When
        ResponseEntity<RsData<ArtistExchangeResponse.List>> response =
                artistDashboardController.getExchangeRequests(testUserDetails, request);

        // Then
        ArtistExchangeResponse.List data = assertSuccessResponse(response, "교환 요청 목록 조회 성공");
        assertAll(
                () -> assertThat(data.summary().total()).isEqualTo(5),
                () -> assertThat(data.content()).hasSize(1),
                () -> assertThat(data.content().getFirst().requestId()).isEqualTo(21L),
                () -> assertThat(data.content().getFirst().status()).isEqualTo("PENDING")
        );
    }

    @Test
    @DisplayName("작가 설정 정보 조회 성공")
    void getSettings_Success() {
        // Given
        ArtistSettingsResponse mockResponse = new ArtistSettingsResponse(
                new ArtistSettingsResponse.Profile("작가명입니다", "자신을 소개하는 글을 입력해주세요.", List.of(), null),
                new ArtistSettingsResponse.Business("서울특별시 강남구 테헤란로 123 2층", null, null, true),
                new ArtistSettingsResponse.Payout(null, "신한", null, null, "VERIFIED"),
                new ArtistSettingsResponse.Permissions(true, false, false)
        );

        given(artistDashboardService.getSettings(eq(TEST_ARTIST_ID))).willReturn(mockResponse);

        // When
        ResponseEntity<RsData<ArtistSettingsResponse>> response =
                artistDashboardController.getSettings(testUserDetails);

        // Then
        ArtistSettingsResponse data = assertSuccessResponse(response, "판매자 설정 조회 성공");
        assertAll(
                () -> assertThat(data.profile().nickname()).isEqualTo("작가명입니다"),
                () -> assertThat(data.business().verified()).isTrue(),
                () -> assertThat(data.payout().status()).isEqualTo("VERIFIED"),
                () -> assertThat(data.permissions().canEditProfile()).isTrue()
        );
    }

    @Test
    @DisplayName("작가 펀딩 목록 조회 성공")
    void getFundings_Success() {
        // Given
        ArtistFundingResponse.Funding mockFunding = new ArtistFundingResponse.Funding(
                456789L, 
                "펀딩 제목입니다", 
                "OPEN", 
                "진행중",
                900000L,
                900000L,
                100.0,
                800,
                "2025-08-01",
                "2025-09-18",
                "2025-09-01",
                "https://example.com/image.jpg",
                null,
                new ArtistFundingResponse.Permissions(true, true, true),
                new ArtistFundingResponse.Flags(true, false, false)
        );

        ArtistFundingResponse.List mockResponse = new ArtistFundingResponse.List(
                List.of(mockFunding),
                DEFAULT_PAGE, DEFAULT_SIZE, 15, 1, false, false
        );

        given(artistDashboardService.getFundings(eq(TEST_ARTIST_ID), any(ArtistFundingSearchRequest.class)))
                .willReturn(mockResponse);

        ArtistFundingSearchRequest request = new ArtistFundingSearchRequest(
                DEFAULT_PAGE, DEFAULT_SIZE, null, null, null, null, null, null, null, "endDate", "ASC");

        // When
        ResponseEntity<RsData<ArtistFundingResponse.List>> response =
                artistDashboardController.getFundings(testUserDetails, request);

        // Then
        ArtistFundingResponse.List data = assertSuccessResponse(response, "내 펀딩 모니터링 조회 성공");
        assertAll(
                () -> assertThat(data.getContent()).hasSize(1),
                () -> assertThat(data.getContent().getFirst().fundingId()).isEqualTo(456789L),
                () -> assertThat(data.getContent().getFirst().status()).isEqualTo("OPEN"),
                () -> assertThat(data.getContent().getFirst().statusText()).isEqualTo("진행중")
        );
    }

    @Test
    @DisplayName("작가 정산내역 조회 성공")
    void getSettlements_Success() {
        // Given
        ArtistSettlementResponse.Summary mockSummary = new ArtistSettlementResponse.Summary(
                new ArtistSettlementResponse.AmountInfo(128000, "총 매출"),
                new ArtistSettlementResponse.AmountInfo(51264, "수수료"),
                new ArtistSettlementResponse.AmountInfo(64000, "순수익")
        );

        ArtistSettlementResponse.Settlement mockSettlement = new ArtistSettlementResponse.Settlement(
                910004L,
                "2025-09-18",
                new ArtistSettlementResponse.Product(101L, "상품명입니다 상품명입니다"),
                18000,
                200,
                17800,
                "PENDING",
                "미지급"
        );

        ArtistSettlementResponse.Table mockTable = new ArtistSettlementResponse.Table(
                List.of(mockSettlement),
                DEFAULT_PAGE, DEFAULT_SIZE, 124, 7, true, false
        );

        ArtistSettlementResponse mockResponse = new ArtistSettlementResponse(
                new ArtistSettlementResponse.Scope(2025, null),
                "Asia/Seoul",
                mockSummary,
                new ArtistSettlementResponse.Chart(
                        new ArtistSettlementResponse.ChartSeries(List.of()),
                        new ArtistSettlementResponse.YDomain(0, 1100000)
                ),
                mockTable,
                LocalDateTime.now()
        );

        given(artistDashboardService.getSettlements(eq(TEST_ARTIST_ID), any(ArtistSettlementSearchRequest.class)))
                .willReturn(mockResponse);

        ArtistSettlementSearchRequest request = new ArtistSettlementSearchRequest(
                2025, null, null, null, DEFAULT_PAGE, DEFAULT_SIZE, "date", "DESC");

        // When
        ResponseEntity<RsData<ArtistSettlementResponse>> response =
                artistDashboardController.getSettlements(testUserDetails, request);

        // Then
        ArtistSettlementResponse data = assertSuccessResponse(response, "정산 내역 조회 성공");
        
        assertAll(
                () -> assertThat(data.scope()).isNotNull(),
                () -> assertThat(data.summary()).isNotNull(),
                () -> assertThat(data.chart()).isNotNull(),
                () -> assertThat(data.table()).isNotNull(),
                () -> assertThat(data.scope().year()).isEqualTo(2025),
                () -> assertThat(data.scope().month()).isNull(),
                () -> assertThat(data.summary().totalSales().amount()).isEqualTo(128000),
                () -> assertThat(data.summary().totalCommission().amount()).isEqualTo(51264),
                () -> assertThat(data.summary().totalNetIncome().amount()).isEqualTo(64000),
                () -> assertThat(data.table().getContent()).hasSize(1),
                () -> assertThat(data.table().getContent().getFirst().settlementId()).isEqualTo(910004L),
                () -> assertThat(data.table().getContent().getFirst().status()).isEqualTo("PENDING"),
                () -> assertThat(data.table().getContent().getFirst().grossAmount()).isEqualTo(18000),
                () -> assertThat(data.table().getContent().getFirst().commission()).isEqualTo(200),
                () -> assertThat(data.table().getContent().getFirst().netAmount()).isEqualTo(17800),
                () -> assertThat(data.table().getTotalElements()).isEqualTo(124),
                () -> assertThat(data.table().getTotalPages()).isEqualTo(7),
                () -> assertThat(data.table().isHasNext()).isTrue(),
                () -> assertThat(data.table().isHasPrevious()).isFalse(),
                () -> assertThat(data.chart().yDomain().min()).isEqualTo(0),
                () -> assertThat(data.chart().yDomain().max()).isEqualTo(1100000)
        );
    }

    @Test
    @DisplayName("작가 정산내역 조회 - 월별 필터 적용")
    void getSettlements_WithMonthFilter() {
        // Given
        ArtistSettlementResponse mockResponse = new ArtistSettlementResponse(
                new ArtistSettlementResponse.Scope(2025, 9),
                "Asia/Seoul",
                new ArtistSettlementResponse.Summary(
                        new ArtistSettlementResponse.AmountInfo(50000, "총 매출"),
                        new ArtistSettlementResponse.AmountInfo(5000, "수수료"),
                        new ArtistSettlementResponse.AmountInfo(45000, "순수익")
                ),
                new ArtistSettlementResponse.Chart(
                        new ArtistSettlementResponse.ChartSeries(List.of()),
                        new ArtistSettlementResponse.YDomain(0, 100000)
                ),
                new ArtistSettlementResponse.Table(List.of(), DEFAULT_PAGE, DEFAULT_SIZE, 0, 0, false, false),
                LocalDateTime.now()
        );

        ArtistSettlementSearchRequest request = new ArtistSettlementSearchRequest(
                2025, 9, "COMPLETED", 101L, DEFAULT_PAGE, DEFAULT_SIZE, "grossAmount", "ASC");

        given(artistDashboardService.getSettlements(eq(TEST_ARTIST_ID), eq(request)))
                .willReturn(mockResponse);

        // When
        ResponseEntity<RsData<ArtistSettlementResponse>> response =
                artistDashboardController.getSettlements(testUserDetails, request);

        // Then
        ArtistSettlementResponse data = assertSuccessResponse(response, "정산 내역 조회 성공");
        assertAll(
                () -> assertThat(data.scope().year()).isEqualTo(2025),
                () -> assertThat(data.scope().month()).isEqualTo(9),
                () -> assertThat(data.summary().totalSales().amount()).isEqualTo(50000),
                () -> assertThat(data.table().getContent()).isEmpty()
        );
    }
}
