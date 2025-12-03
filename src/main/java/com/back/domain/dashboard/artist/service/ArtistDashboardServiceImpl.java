package com.back.domain.dashboard.artist.service;

import com.back.domain.dashboard.artist.dto.request.*;
import com.back.domain.dashboard.artist.dto.response.*;
import com.back.domain.follow.entity.Follow;
import com.back.domain.follow.repository.FollowRepository;
import com.back.domain.funding.entity.Funding;
import com.back.domain.funding.entity.FundingStatus;
import com.back.domain.funding.repository.FundingContributionRepository;
import com.back.domain.funding.repository.FundingRepository;
import com.back.domain.order.refund.repository.RefundRepository;
import com.back.domain.product.product.entity.Product;
import com.back.domain.product.product.entity.SellingStatus;
import com.back.domain.product.product.repository.ProductRepository;
import com.google.analytics.data.v1beta.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 작가용 대시보드 서비스 구현체
 * 2025.09.29 수정 - getProducts() 실제 DB 연동
 * 2025.09.30 수정 - getFundings() 실제 DB 연동
 * 2025.10.01 추가 - getTrafficSources() GA4 유입 경로 분석
 * 2025.10.02 JWT 표준 패턴 적용
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ArtistDashboardServiceImpl implements ArtistDashboardService {

    private final ProductRepository productRepository;
    private final FundingRepository fundingRepository;
    private final FundingContributionRepository fundingContributionRepository;
    private final RefundRepository refundRepository;
    private final com.back.domain.order.exchange.repository.ExchangeRepository exchangeRepository;
    private final com.back.domain.order.order.repository.OrderRepository orderRepository;
    private final BetaAnalyticsDataClient analyticsDataClient;
    private final com.back.domain.artist.repository.ArtistProfileRepository artistProfileRepository;
    private final com.back.domain.payment.moriCash.service.MoriCashBalanceService moriCashBalanceService;
    private final com.back.domain.payment.moriCash.repository.MoriCashBalanceRepository moriCashBalanceRepository;
    private final com.back.domain.payment.settlement.repository.SettlementRepository settlementRepository;
    private final com.back.domain.user.repository.UserRepository userRepository;
    private final com.back.domain.payment.cash.repository.CashTransactionRepository cashTransactionRepository;
    private final FollowRepository followRepository;  // ✅ 추가: 팔로우 기능

    @Value("${google.analytics.property-id}")
    private String propertyId;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy. MM. dd");

    @Override
    public ArtistMainResponse getMainStats(Long artistId, ArtistMainStatsRequest request) {
        log.info("작가 메인 대시보드 통계 조회 시작 - artistId: {}, timezone: {}", artistId, request.tz());

        // 1. 작가 프로필 정보 조회
        com.back.domain.artist.entity.ArtistProfile artistProfile = artistProfileRepository
                .findByUserId(artistId)
                .orElseThrow(() -> new com.back.global.exception.ServiceException("404", "작가 프로필을 찾을 수 없습니다."));

        // 프로필 정보
        ArtistMainResponse.Profile profile = new ArtistMainResponse.Profile(
                artistProfile.getUser().getId(),
                artistProfile.getArtistName(),
                artistProfile.getEmail(),
                artistProfile.getProfileImageUrl()
        );

        // 2. 통계 정보 조회 (한 번의 쿼리로)
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startOfDay = now.toLocalDate().atStartOfDay();
        LocalDateTime endOfDay = now.toLocalDate().atTime(23, 59, 59);

        com.back.domain.dashboard.artist.dto.DashboardStatsDto stats =
                orderRepository.getArtistDashboardStats(artistId, startOfDay, endOfDay);

        // 팔로워 수 조회 (Follow 테이블에서 실시간 COUNT)
        int followerCount = (int) followRepository.countByFollowingArtistId(artistProfile.getId());

        // 오늘의 매출 조회 부분입니다. (null 처리)
        int todaysSales = stats.todaysSales() != null ? stats.todaysSales().intValue() : 0;

        // 오늘의 주문 수 조회 부분입니다. (null 처리)
        int todaysOrders = stats.todaysOrderCount() != null ? stats.todaysOrderCount().intValue() : 0;

        // 상품 수 조회 부분입니다.
        int productCount = artistProfile.getProductCount();

        // 대기중인 주문 수 (결제완료 상태)
        int pendingOrders = calculatePendingOrders(artistId);

        ArtistMainResponse.Stats statsResponse = new ArtistMainResponse.Stats(
                followerCount,
                productCount,
                todaysSales,
                todaysOrders,
                stats.totalSales() != null ? stats.totalSales().intValue() : 0,
                stats.totalOrderCount() != null ? stats.totalOrderCount().intValue() : 0,
                0.0,  // TODO: 평균 평점 (리뷰 기능 구현 시)
                pendingOrders
        );

        // 3. 트렌드 정보 (Artist Profile ID도 함께 전달)
        ArtistMainResponse.Trends trends = createTrendsData(artistId, artistProfile.getId(), request.range(), request.tz());

        // 4. 알림 정보 (빈 데이터 - 다음 단계에서 구현)
        ArtistMainResponse.Notifications notifications = new ArtistMainResponse.Notifications(
                List.of(),
                List.of()
        );

        // 5. 유입 경로 정보 (GA4)
        ArtistMainResponse.TrafficSources trafficSources = getTrafficSourcesForMain(artistId, request.tz());

        log.info("작가 메인 대시보드 통계 조회 완료 - artistId: {}, 오늘 매출: {}, 오늘 주문: {}",
                artistId, todaysSales, todaysOrders);

        return new ArtistMainResponse(
                profile,
                statsResponse,
                trends,
                notifications,
                trafficSources,
                LocalDateTime.now(),
                request.tz()
        );
    }

    /**
     * 대기중인 주문 수 계산
     */
    private int calculatePendingOrders(Long artistId) {
        try {
            Page<com.back.domain.order.order.entity.Order> pendingOrders = orderRepository.findOrdersByArtist(
                    artistId,
                    com.back.domain.order.order.entity.OrderStatus.PAYMENT_COMPLETED,
                    null, null, null,
                    PageRequest.of(0, 1)
            );
            return (int) pendingOrders.getTotalElements();
        } catch (Exception e) {
            log.error("대기중인 주문 수 조회 실패 - artistId: {}", artistId, e);
            return 0;
        }
    }

    /**
     * 트렌드 데이터 생성 (매출 + 주문 수)
     */
    private ArtistMainResponse.Trends createTrendsData(Long artistId, Long artistProfileId, String range, String timezone) {
        // 1. 기간 계산
        LocalDateTime endDate = LocalDateTime.now();
        LocalDateTime startDate;
        LocalDateTime compareEndDate;
        LocalDateTime compareStartDate;
        String interval = "day";
        int maxPoints = 30;

        // range 매핑: 1D/7D/30D/3M/6M/1Y/CUSTOM → 1M/3M/6M/1Y/ALL
        String normalizedRange = range != null ? range : "30D";

        switch (normalizedRange) {
            case "1D" -> {
                startDate = endDate.minusDays(1);
                compareStartDate = startDate.minusDays(1);
                compareEndDate = startDate;
                maxPoints = 1;
            }
            case "7D" -> {
                startDate = endDate.minusDays(7);
                compareStartDate = startDate.minusDays(7);
                compareEndDate = startDate;
                maxPoints = 7;
            }
            case "30D" -> {
                startDate = endDate.minusDays(30);
                compareStartDate = startDate.minusDays(30);
                compareEndDate = startDate;
                maxPoints = 30;
            }
            case "3M" -> {
                startDate = endDate.minusMonths(3);
                compareStartDate = startDate.minusMonths(3);
                compareEndDate = startDate;
                maxPoints = 90;
            }
            case "6M" -> {
                startDate = endDate.minusMonths(6);
                compareStartDate = startDate.minusMonths(6);
                compareEndDate = startDate;
                maxPoints = 180;
            }
            case "1Y" -> {
                startDate = endDate.minusYears(1);
                compareStartDate = startDate.minusYears(1);
                compareEndDate = startDate;
                maxPoints = 365;
            }
            case "CUSTOM" -> {
                // CUSTOM은 현재 30D로 처리 (추후 from/to 파라미터 추가 시 구현)
                startDate = endDate.minusDays(30);
                compareStartDate = startDate.minusDays(30);
                compareEndDate = startDate;
                maxPoints = 30;
            }
            default -> {
                startDate = endDate.minusDays(30);
                compareStartDate = startDate.minusDays(30);
                compareEndDate = startDate;
                maxPoints = 30;
            }
        }

        // 2. 일별 트렌드 데이터 조회 (매출 + 주문 수 동시 조회)
        List<com.back.domain.dashboard.artist.dto.DailyTrendDto> dailyTrends =
                orderRepository.findDailyTrendsByArtist(artistId, startDate, endDate);

        // 3. 비교 기간 데이터 조회
        List<com.back.domain.dashboard.artist.dto.DailyTrendDto> compareTrends =
                orderRepository.findDailyTrendsByArtist(artistId, compareStartDate, compareEndDate);

        // 4. 매출 시계열 데이터 생성
        List<ArtistMainResponse.DataPoint> salesPoints = dailyTrends.stream()
                .map(d -> new ArtistMainResponse.DataPoint(
                        d.date().toString(),
                        d.salesAmount().intValue()
                ))
                .toList();

        int totalSales = dailyTrends.stream()
                .mapToInt(d -> d.salesAmount().intValue())
                .sum();

        ArtistMainResponse.SeriesData salesSeries = new ArtistMainResponse.SeriesData(
                "원",
                salesPoints,
                totalSales
        );

        // 5. 주문 수 시계열 데이터 생성
        List<ArtistMainResponse.DataPoint> orderPoints = dailyTrends.stream()
                .map(d -> new ArtistMainResponse.DataPoint(
                        d.date().toString(),
                        d.orderCount().intValue()
                ))
                .toList();

        int totalOrders = dailyTrends.stream()
                .mapToInt(d -> d.orderCount().intValue())
                .sum();

        ArtistMainResponse.SeriesData orderSeries = new ArtistMainResponse.SeriesData(
                "건",
                orderPoints,
                totalOrders
        );

        // 6. 팔로워 시계열 데이터 (Follow 엔티티에서 직접 집계)
        ArtistMainResponse.SeriesData followerSeries = createFollowerSeriesFromFollowEntities(
                artistProfileId,
                startDate.toLocalDate(),
                endDate.toLocalDate()
        );

        ArtistMainResponse.Series series = new ArtistMainResponse.Series(
                salesSeries,
                orderSeries,
                followerSeries
        );

        // 7. 변화량 계산
        int compareSales = compareTrends.stream()
                .mapToInt(d -> d.salesAmount().intValue())
                .sum();

        int compareOrders = compareTrends.stream()
                .mapToInt(d -> d.orderCount().intValue())
                .sum();

        ArtistMainResponse.ChangeData salesChange = calculateChange(totalSales, compareSales);
        ArtistMainResponse.ChangeData orderChange = calculateChange(totalOrders, compareOrders);

        // 팔로워 변화량 계산
        int currentFollowerCount = (int) followRepository.countByFollowingArtistId(artistProfileId);
        List<Follow> allFollows = followRepository.findFollowersByArtistId(artistProfileId);
        int compareFollowerCount = (int) allFollows.stream()
                .filter(f -> !f.getCreateDate().isAfter(compareEndDate))
                .count();
        ArtistMainResponse.ChangeData followerChange = calculateChange(currentFollowerCount, compareFollowerCount);

        ArtistMainResponse.Changes changes = new ArtistMainResponse.Changes(
                salesChange,
                orderChange,
                followerChange
        );

        // 8. 메타 정보
        ArtistMainResponse.Meta meta = new ArtistMainResponse.Meta(
                normalizedRange,
                startDate.toLocalDate().toString(),
                endDate.toLocalDate().toString(),
                interval,
                timezone,
                maxPoints,
                new ArtistMainResponse.Compare(
                        compareStartDate.toLocalDate().toString(),
                        compareEndDate.toLocalDate().toString()
                )
        );

        return new ArtistMainResponse.Trends(meta, series, changes);
    }

    /**
     * 변화량 계산 (delta, rate)
     */
    private ArtistMainResponse.ChangeData calculateChange(int current, int previous) {
        int delta = current - previous;
        double rate = 0.0;

        if (previous > 0) {
            rate = ((double) delta / previous) * 100;
        } else if (current > 0) {
            rate = 100.0; // 이전 값이 0이고 현재 값이 있으면 100% 증가
        }

        return new ArtistMainResponse.ChangeData(delta, rate);
    }

    /**
     * 메인 대시보드용 유입 경로 데이터 조회 (간소화 버전)
     */
    private ArtistMainResponse.TrafficSources getTrafficSourcesForMain(Long artistId, String timezone) {
        try {
            // 기존 getTrafficSources 메서드 호출 (7일 기준)
            ArtistTrafficSourceResponse fullResponse = getTrafficSources(artistId, 7, timezone);


            // 메인 대시보드용으로 간소화
            ArtistMainResponse.Summary summary = new ArtistMainResponse.Summary(
                    fullResponse.summary().totalSessions(),
                    fullResponse.summary().totalUsers(),
                    fullResponse.summary().conversions(),
                    fullResponse.summary().conversionRate(),
                    fullResponse.summary().topSource()
            );

            // 상위 5개 유입 경로만
            List<ArtistMainResponse.Source> sources = fullResponse.sources().stream()
                    .limit(5)
                    .map(source -> new ArtistMainResponse.Source(
                            source.name(),
                            source.sessions(),
                            source.users(),
                            source.share()
                    ))
                    .toList();

            // 차트 데이터
            List<ArtistMainResponse.ChartData> chartData = fullResponse.chart().data().stream()
                    .limit(5)
                    .map(data -> new ArtistMainResponse.ChartData(
                            data.name(),
                            data.value(),
                            data.percentage(),
                            data.color()
                    ))
                    .toList();

            ArtistMainResponse.Chart chart = new ArtistMainResponse.Chart(chartData);

            return new ArtistMainResponse.TrafficSources(summary, sources, chart);

        } catch (Exception e) {
            log.error("메인 대시보드 유입 경로 조회 중 오류 발생", e);

            // 오류 발생 시 빈 데이터 반환
            return new ArtistMainResponse.TrafficSources(
                    new ArtistMainResponse.Summary(0, 0, 0, 0.0, "없음"),
                    List.of(),
                    new ArtistMainResponse.Chart(List.of())
            );
        }
    }

    @Override
    public ArtistProductResponse.List getProducts(Long artistId, ArtistProductSearchRequest request) {
        log.info("작가 상품 목록 조회 시작 - artistId: {}, page: {}, size: {}, keyword: {}, selling: {}, sort: {}, order: {}",
                artistId, request.page(), request.size(), request.keyword(), request.selling(), request.sort(), request.order());

        // Repository를 통한 실제 DB 조회
        Page<Product> productPage = productRepository.findProductsByArtist(
                artistId, request.keyword(), request.selling(), request.sort(), request.order(),
                PageRequest.of(request.page(), request.size())
        );

        // Entity → DTO 변환
        List<ArtistProductResponse.Product> content = productPage.getContent().stream()
                .map(this::convertToProductDto)
                .toList();

        int totalPages = productPage.getTotalPages();
        long totalElements = productPage.getTotalElements();
        boolean hasNext = productPage.hasNext();
        boolean hasPrevious = productPage.hasPrevious();

        log.info("작가 상품 목록 조회 완료 - 조회된 상품 수: {}, 전체: {}", content.size(), totalElements);

        return new ArtistProductResponse.List(content, request.page(), request.size(), totalElements, totalPages, hasNext, hasPrevious);
    }

    /**
     * Product 엔티티를 DTO로 변환
     */
    private ArtistProductResponse.Product convertToProductDto(Product product) {
        return new ArtistProductResponse.Product(
                product.getProductUuid(),
                product.getName(),
                product.getPrice(),
                product.getDiscountRate(),
                product.getDiscountPrice(),
                product.getSellingStatus().name(),
                convertStatusToKorean(product.getSellingStatus()),
                product.getCreateDate().format(DATE_FORMATTER)
        );
    }

    /**
     * SellingStatus Enum을 한글로 변환
     */
    private String convertStatusToKorean(SellingStatus status) {
        return switch (status) {
            case BEFORE_SELLING -> "판매 전";
            case SELLING -> "판매중";
            case SOLD_OUT -> "품절";
            case END_OF_SALE -> "판매종료";
        };
    }

    @Override
    public ArtistCashResponse.Balance getCashBalance(Long artistId) {
        log.info("작가 지갑 잔액 조회 - artistId: {}", artistId);

        // 1. 작가(User) 조회
        com.back.domain.user.entity.User artist = userRepository.findById(artistId)
                .orElseThrow(() -> new com.back.global.exception.ServiceException("404", "작가를 찾을 수 없습니다."));

        // 2. MoriCashBalanceService를 통해 모리캐시 잔액 조회
        com.back.domain.payment.moriCash.dto.response.MoriCashBalanceResponseDto balanceDto =
                moriCashBalanceService.getBalance(artist);

        // 3. 응답 DTO 변환
        // TODO: pendingSettlement, pendingWithdrawal 계산 로직 추가 필요
        // 현재는 availableBalance를 withdrawable로 사용
        return new ArtistCashResponse.Balance(
                balanceDto.getTotalBalance(),        // currentBalance
                0,                                    // pendingSettlement (정산 대기 금액 - 추후 구현)
                balanceDto.getFrozenBalance(),       // pendingWithdrawal (환전 처리 중)
                balanceDto.getAvailableBalance(),    // withdrawable (환전 가능 금액)
                "KRW",                                // currency
                java.time.LocalDateTime.now()        // updatedAt
        );
    }

    @Override
    public ArtistCashHistoryResponse.List getCashHistory(Long artistId, ArtistCashHistorySearchRequest request) {
        log.info("작가 캐시 내역 조회 - artistId: {}, page: {}, size: {}, type: {}, status: {}, dateFrom: {}, dateTo: {}",
                artistId, request.page(), request.size(), request.type(), request.status(), request.dateFrom(), request.dateTo());

        // 1. 작가(User) 조회
        com.back.domain.user.entity.User artist = userRepository.findById(artistId)
                .orElseThrow(() -> new com.back.global.exception.ServiceException("404", "작가를 찾을 수 없습니다."));

        // 2. 필터 조건 변환
        com.back.domain.payment.cash.entity.CashTransactionType transactionType = null;
        if (request.type() != null && !request.type().isBlank()) {
            try {
                // DEPOSIT -> CHARGING, WITHDRAWAL -> EXCHANGE
                transactionType = switch (request.type()) {
                    case "DEPOSIT" -> com.back.domain.payment.cash.entity.CashTransactionType.CHARGING;
                    case "WITHDRAWAL" -> com.back.domain.payment.cash.entity.CashTransactionType.EXCHANGE;
                    default -> throw new IllegalArgumentException("Invalid transaction type: " + request.type());
                };
            } catch (IllegalArgumentException e) {
                log.warn("잘못된 거래 유형: {}", request.type());
            }
        }

        com.back.domain.payment.cash.entity.CashTransactionStatus status = null;
        if (request.status() != null && !request.status().isBlank()) {
            try {
                status = com.back.domain.payment.cash.entity.CashTransactionStatus.valueOf(request.status());
            } catch (IllegalArgumentException e) {
                log.warn("잘못된 거래 상태: {}", request.status());
            }
        }

        // 3. 날짜 파싱
        LocalDateTime startDate = null;
        LocalDateTime endDate = null;

        if (request.dateFrom() != null && !request.dateFrom().isBlank()) {
            try {
                startDate = java.time.LocalDate.parse(request.dateFrom()).atStartOfDay();
            } catch (java.time.format.DateTimeParseException e) {
                log.warn("잘못된 시작 날짜 형식: {}", request.dateFrom());
            }
        }

        if (request.dateTo() != null && !request.dateTo().isBlank()) {
            try {
                endDate = java.time.LocalDate.parse(request.dateTo()).atTime(23, 59, 59);
            } catch (java.time.format.DateTimeParseException e) {
                log.warn("잘못된 종료 날짜 형식: {}", request.dateTo());
            }
        }

        // 4. 정렬 설정
        org.springframework.data.domain.Sort sort = createCashHistorySort(request.sort(), request.order());
        PageRequest pageRequest = PageRequest.of(request.page(), request.size(), sort);

        // 5. Repository를 통한 실제 DB 조회
        Page<com.back.domain.payment.cash.entity.CashTransaction> transactionPage =
                cashTransactionRepository.findCashTransactionsByUserWithFilters(
                        artist,
                        transactionType,
                        status,
                        startDate,
                        endDate,
                        pageRequest
                );

        // 6. Entity → DTO 변환
        List<ArtistCashHistoryResponse.Transaction> content = transactionPage.getContent().stream()
                .map(this::convertToCashHistoryDto)
                .toList();

        // 7. 기간별 요약 계산
        ArtistCashHistoryResponse.Summary summary = calculateCashHistorySummary(
                artist, startDate, endDate);

        int totalPages = transactionPage.getTotalPages();
        long totalElements = transactionPage.getTotalElements();
        boolean hasNext = transactionPage.hasNext();
        boolean hasPrevious = transactionPage.hasPrevious();

        log.info("작가 캐시 내역 조회 완료 - 조회된 거래 수: {}, 전체: {}", content.size(), totalElements);

        return new ArtistCashHistoryResponse.List(
                summary,
                content,
                request.page(),
                request.size(),
                totalElements,
                totalPages,
                hasNext,
                hasPrevious
        );
    }

    /**
     * CashTransaction 정렬 생성
     */
    private org.springframework.data.domain.Sort createCashHistorySort(String sortField, String sortOrder) {
        if (sortField == null || sortField.isBlank()) {
            sortField = "transactedAt";
        }
        if (sortOrder == null || sortOrder.isBlank()) {
            sortOrder = "DESC";
        }

        org.springframework.data.domain.Sort.Direction direction =
                "ASC".equalsIgnoreCase(sortOrder)
                        ? org.springframework.data.domain.Sort.Direction.ASC
                        : org.springframework.data.domain.Sort.Direction.DESC;

        return switch (sortField) {
            case "amount" -> org.springframework.data.domain.Sort.by(direction, "amount");
            case "type" -> org.springframework.data.domain.Sort.by(direction, "transactionType");
            case "status" -> org.springframework.data.domain.Sort.by(direction, "status");
            default -> org.springframework.data.domain.Sort.by(direction, "completedAt", "createDate");
        };
    }

    /**
     * CashTransaction 엔티티를 Transaction DTO로 변환
     */
    private ArtistCashHistoryResponse.Transaction convertToCashHistoryDto(
            com.back.domain.payment.cash.entity.CashTransaction transaction) {

        // 거래 일시 (완료 시간 우선, 없으면 생성 시간)
        String transactedAt = transaction.getCompletedAt() != null
                ? transaction.getCompletedAt().format(java.time.format.DateTimeFormatter.ofPattern("yyyy. MM. dd HH:mm"))
                : transaction.getCreateDate().format(java.time.format.DateTimeFormatter.ofPattern("yyyy. MM. dd HH:mm"));

        // 거래 유형 변환 (CHARGING -> DEPOSIT, EXCHANGE -> WITHDRAWAL)
        String type = transaction.isCharging() ? "DEPOSIT" : "WITHDRAWAL";
        String typeText = transaction.isCharging() ? "입금" : "환전";

        // 입금액/환전액 구분
        int depositAmount = transaction.isCharging() ? transaction.getAmount() : 0;
        int withdrawalAmount = transaction.isExchange() ? transaction.getAmount() : 0;

        // 거래 후 잔액
        int balanceAfter = transaction.getBalanceAfter() != null ? transaction.getBalanceAfter() : 0;

        // 거래 방법
        String method = transaction.getPaymentMethod() != null ? transaction.getPaymentMethod() : "UNKNOWN";
        String methodText = convertPaymentMethodToKorean(transaction.getPaymentMethod());

        // 상태
        String status = transaction.getStatus().name();

        // 메모 (실패 사유, 취소 사유 등)
        String note = "";
        if (transaction.getStatus() == com.back.domain.payment.cash.entity.CashTransactionStatus.FAILED) {
            note = transaction.getFailureReason() != null ? transaction.getFailureReason() : "처리 실패";
        } else if (transaction.getStatus() == com.back.domain.payment.cash.entity.CashTransactionStatus.CANCELLED) {
            note = transaction.getCancellationReason() != null ? transaction.getCancellationReason() : "처리 취소";
        }

        return new ArtistCashHistoryResponse.Transaction(
                transaction.getId().toString(),
                transactedAt,
                type,
                typeText,
                depositAmount,
                withdrawalAmount,
                balanceAfter,
                method,
                methodText,
                status,
                note
        );
    }

    /**
     * 결제 수단을 한글로 변환
     * 작가 대시보드에서는 모리캐시(정산금 입금)와 계좌이체(환전) 2가지만 사용
     */
    private String convertPaymentMethodToKorean(String paymentMethod) {
        if (paymentMethod == null) {
            return "기타";
        }

        // 입금 수단: 모리캐시 (정산금이 모리캐시로 입금됨)
        // 환전 수단: 계좌이체 (모리캐시를 실제 계좌로 환전)
        return switch (paymentMethod.toUpperCase()) {
            case "WALLET", "MORICASH" -> "모리캐시";
            case "BANK_TRANSFER", "BANK" -> "계좌이체";
            default -> paymentMethod;
        };
    }

    /**
     * 기간별 입금/환전 요약 계산
     */
    private ArtistCashHistoryResponse.Summary calculateCashHistorySummary(
            com.back.domain.user.entity.User artist,
            LocalDateTime startDate,
            LocalDateTime endDate) {

        // CashTransactionRepository 사용
        int periodDepositTotal = cashTransactionRepository.getPeriodDepositTotal(artist, startDate, endDate);
        int periodWithdrawalTotal = cashTransactionRepository.getPeriodWithdrawalTotal(artist, startDate, endDate);
        int periodNet = periodDepositTotal - periodWithdrawalTotal;

        return new ArtistCashHistoryResponse.Summary(
                periodDepositTotal,
                periodWithdrawalTotal,
                periodNet
        );
    }

    @Override
    public ArtistOrderResponse.List getOrders(Long artistId, ArtistOrderSearchRequest request) {
        log.info("작가 주문 내역 조회 시작 - artistId: {}, page: {}, size: {}, status: {}, keyword: {}, startDate: {}, endDate: {}",
                artistId, request.page(), request.size(), request.status(), request.keyword(), request.startDate(), request.endDate());

        // status 문자열을 OrderStatus enum으로 변환
        com.back.domain.order.order.entity.OrderStatus orderStatus = null;
        if (request.status() != null && !request.status().isBlank()) {
            try {
                orderStatus = com.back.domain.order.order.entity.OrderStatus.valueOf(request.status());
            } catch (IllegalArgumentException e) {
                log.warn("잘못된 주문 상태값: {}", request.status());
            }
        }

        // 날짜 파싱
        java.time.LocalDateTime startDateTime = null;
        java.time.LocalDateTime endDateTime = null;

        if (request.startDate() != null && !request.startDate().isBlank()) {
            try {
                startDateTime = java.time.LocalDate.parse(request.startDate()).atStartOfDay();
            } catch (java.time.format.DateTimeParseException e) {
                log.warn("잘못된 시작 날짜 형식: {}", request.startDate());
            }
        }

        if (request.endDate() != null && !request.endDate().isBlank()) {
            try {
                endDateTime = java.time.LocalDate.parse(request.endDate()).atTime(23, 59, 59);
            } catch (java.time.format.DateTimeParseException e) {
                log.warn("잘못된 종료 날짜 형식: {}", request.endDate());
            }
        }

        // 상품명 정렬 여부 확인
        boolean isProductNameSort = "productName".equals(request.sort());
        
        Page<com.back.domain.order.order.entity.Order> orderPage;

        if (isProductNameSort) {
            // 상품명 정렬: 별도 메서드 사용
            String direction = request.order() != null ? request.order() : "DESC";
            PageRequest pageRequest = PageRequest.of(request.page(), request.size());
            
            orderPage = orderRepository.findOrdersByArtistSortedByProductName(
                    artistId,
                    orderStatus,
                    request.keyword(),
                    startDateTime,
                    endDateTime,
                    direction,
                    pageRequest
            );
        } else {
            // 일반 정렬: 동적 정렬 처리
            org.springframework.data.domain.Sort sort = createOrderSort(request.sort(), request.order());
            PageRequest pageRequest = PageRequest.of(request.page(), request.size(), sort);

            orderPage = orderRepository.findOrdersByArtist(
                    artistId,
                    orderStatus,
                    request.keyword(),
                    startDateTime,
                    endDateTime,
                    pageRequest
            );
        }

        // ID 리스트 추출
        List<Long> orderIds = orderPage.getContent().stream()
                .map(com.back.domain.order.order.entity.Order::getId)
                .toList();

        // Fetch Join으로 상세 정보 조회 (N+1 방지)
        List<com.back.domain.order.order.entity.Order> ordersWithDetails = orderIds.isEmpty()
                ? List.of()
                : orderRepository.findOrdersWithDetailsByArtist(orderIds, artistId);

        // Entity → DTO 변환
        List<ArtistOrderResponse.Order> content = ordersWithDetails.stream()
                .map(order -> convertToOrderDto(order, artistId))
                .toList();

        // 상태별 요약 정보 계산
        ArtistOrderResponse.Summary summary = calculateOrderSummary(artistId);

        int totalPages = orderPage.getTotalPages();
        long totalElements = orderPage.getTotalElements();
        boolean hasNext = orderPage.hasNext();
        boolean hasPrevious = orderPage.hasPrevious();

        log.info("작가 주문 내역 조회 완료 - 조회된 주문 수: {}, 전체: {}", content.size(), totalElements);

        return new ArtistOrderResponse.List(
                summary,
                content,
                request.page(),
                request.size(),
                totalElements,
                totalPages,
                hasNext,
                hasPrevious
        );
    }

    /**
     * 주문 정렬 생성
     */
    private org.springframework.data.domain.Sort createOrderSort(String sortField, String sortOrder) {
        if (sortField == null || sortField.isBlank()) {
            sortField = "orderDate";
        }
        if (sortOrder == null || sortOrder.isBlank()) {
            sortOrder = "DESC";
        }

        org.springframework.data.domain.Sort.Direction direction =
                "ASC".equalsIgnoreCase(sortOrder)
                        ? org.springframework.data.domain.Sort.Direction.ASC
                        : org.springframework.data.domain.Sort.Direction.DESC;

        return switch (sortField) {
            case "status" -> org.springframework.data.domain.Sort.by(direction, "status");
            case "totalAmount" -> org.springframework.data.domain.Sort.by(direction, "totalAmount");
            case "customerName" -> org.springframework.data.domain.Sort.by(direction, "user.name");
            case "productName" -> null; // 상품명 정렬은 Repository에서 처리
            default -> org.springframework.data.domain.Sort.by(direction, "orderDate");
        };
    }

    /**
     * Order 엔티티를 OrderResponse.Order DTO로 변환
     */
    private ArtistOrderResponse.Order convertToOrderDto(com.back.domain.order.order.entity.Order order, Long artistId) {
        // 작가의 상품만 필터링
        List<com.back.domain.order.orderItem.entity.OrderItem> artistOrderItems = order.getOrderItems().stream()
                .filter(item -> item.getProduct().getUser().getId().equals(artistId))
                .toList();

        // 상품 요약 생성 (첫 번째 상품명 + 나머지 개수)
        String productSummary = "";
        int itemCount = artistOrderItems.size();

        if (!artistOrderItems.isEmpty()) {
            String firstName = artistOrderItems.get(0).getProduct().getName();
            if (itemCount > 1) {
                productSummary = firstName + " 외 " + (itemCount - 1) + "건";
            } else {
                productSummary = firstName;
            }
        }

        // 총 주문 금액 계산 (작가의 상품만)
        int totalAmount = artistOrderItems.stream()
                .mapToInt(item -> item.getPrice().intValue() * item.getQuantity())
                .sum();

        // 구매자 정보
        com.back.domain.user.entity.User buyer = order.getUser();
        ArtistOrderResponse.Buyer buyerDto = new ArtistOrderResponse.Buyer(
                buyer.getId(),
                buyer.getName(),  // nickname 대신 name 사용
                buyer.getName()
        );

        // 배송 정보 (배송 엔티티가 없으므로 주문 상태 기반)
        String shipmentStatus = convertOrderStatusToShipmentStatus(order.getStatus());
        ArtistOrderResponse.Shipment shipmentDto = new ArtistOrderResponse.Shipment(
                shipmentStatus,
                null,  // 운송장 번호 (배송 엔티티에서 가져와야 함)
                null   // 택배사 (배송 엔티티에서 가져와야 함)
        );

        // 상태 텍스트 변환
        String statusText = convertOrderStatusToKorean(order.getStatus());

        // 권한 정보
        boolean canChangeStatus = order.getStatus() == com.back.domain.order.order.entity.OrderStatus.PAYMENT_COMPLETED ||
                order.getStatus() == com.back.domain.order.order.entity.OrderStatus.PREPARING_SHIPMENT;
        boolean canCancel = order.getStatus() == com.back.domain.order.order.entity.OrderStatus.PAYMENT_COMPLETED;

        ArtistOrderResponse.Permissions permissions = new ArtistOrderResponse.Permissions(
                canChangeStatus,
                canCancel
        );

        return new ArtistOrderResponse.Order(
                order.getId().toString(),
                order.getOrderNumber(),
                order.getOrderDate().format(DateTimeFormatter.ofPattern("yyyy. MM. dd HH:mm")),
                order.getStatus().name(),
                statusText,
                totalAmount,
                productSummary,
                itemCount,
                buyerDto,
                shipmentDto,
                permissions
        );
    }

    /**
     * OrderStatus를 배송 상태로 변환
     */
    private String convertOrderStatusToShipmentStatus(com.back.domain.order.order.entity.OrderStatus status) {
        return switch (status) {
            case PAYMENT_COMPLETED -> "발주 전";
            case PREPARING_SHIPMENT -> "배송 준비중";
            case SHIPPING -> "배송 중";
            case DELIVERED -> "배송 완료";
            default -> "기타";
        };
    }

    /**
     * OrderStatus Enum을 한글로 변환
     */
    private String convertOrderStatusToKorean(com.back.domain.order.order.entity.OrderStatus status) {
        return switch (status) {
            case PAYMENT_COMPLETED -> "결제완료";
            case PREPARING_SHIPMENT -> "배송준비중";
            case SHIPPING -> "배송중";
            case DELIVERED -> "배송완료";
            case CANCELLATION_REQUESTED -> "취소요청";
            case CANCELLATION_COMPLETED -> "취소완료";
            case REFUND_REQUESTED -> "환불요청";
            case REFUND_COMPLETED -> "환불완료";
            case EXCHANGE_REQUESTED -> "교환요청";
            case EXCHANGE_COMPLETED -> "교환완료";
        };
    }

    /**
     * 작가의 주문 상태별 요약 정보 계산
     */
    private ArtistOrderResponse.Summary calculateOrderSummary(Long artistId) {
        // 상태별 카운트 조회 (실제 구현에서는 COUNT 쿼리 사용 권장)
        int total = 0;
        int pending = 0;
        int preparing = 0;
        int shipped = 0;
        int delivered = 0;
        int canceled = 0;

        try {
            // PAYMENT_COMPLETED 카운트
            Page<com.back.domain.order.order.entity.Order> pendingOrders = orderRepository.findOrdersByArtist(
                    artistId, com.back.domain.order.order.entity.OrderStatus.PAYMENT_COMPLETED,
                    null, null, null, PageRequest.of(0, 1));
            pending = (int) pendingOrders.getTotalElements();

            // PREPARING_SHIPMENT 카운트
            Page<com.back.domain.order.order.entity.Order> preparingOrders = orderRepository.findOrdersByArtist(
                    artistId, com.back.domain.order.order.entity.OrderStatus.PREPARING_SHIPMENT,
                    null, null, null, PageRequest.of(0, 1));
            preparing = (int) preparingOrders.getTotalElements();

            // SHIPPING 카운트
            Page<com.back.domain.order.order.entity.Order> shippedOrders = orderRepository.findOrdersByArtist(
                    artistId, com.back.domain.order.order.entity.OrderStatus.SHIPPING,
                    null, null, null, PageRequest.of(0, 1));
            shipped = (int) shippedOrders.getTotalElements();

            // DELIVERED 카운트
            Page<com.back.domain.order.order.entity.Order> deliveredOrders = orderRepository.findOrdersByArtist(
                    artistId, com.back.domain.order.order.entity.OrderStatus.DELIVERED,
                    null, null, null, PageRequest.of(0, 1));
            delivered = (int) deliveredOrders.getTotalElements();

            // 전체 카운트 (null status로 조회)
            Page<com.back.domain.order.order.entity.Order> allOrders = orderRepository.findOrdersByArtist(
                    artistId, null, null, null, null, PageRequest.of(0, 1));
            total = (int) allOrders.getTotalElements();

            // canceled는 total에서 계산
            canceled = total - (pending + preparing + shipped + delivered);
            if (canceled < 0) canceled = 0;

        } catch (Exception e) {
            log.error("주문 요약 정보 계산 중 오류 발생 - artistId: {}", artistId, e);
        }

        return new ArtistOrderResponse.Summary(total, pending, preparing, shipped, delivered, canceled);
    }

    @Override
    public ArtistCancellationResponse.List getCancellationRequests(Long artistId, ArtistCancellationSearchRequest request) {
        log.info("작가 취소 요청 목록 조회 시작 - artistId: {}, page: {}, size: {}, status: {}, keyword: {}",
                artistId, request.page(), request.size(), request.status(), request.keyword());

        // status 문자열을 RefundStatus enum으로 변환
        com.back.domain.order.refund.entity.Refund.RefundStatus refundStatus = null;
        if (request.status() != null && !request.status().isBlank()) {
            try {
                refundStatus = com.back.domain.order.refund.entity.Refund.RefundStatus.valueOf(request.status());
            } catch (IllegalArgumentException e) {
                log.warn("잘못된 취소 요청 상태값: {}", request.status());
            }
        }

        // Repository를 통한 실제 DB 조회 (최신순 정렬)
        Page<com.back.domain.order.refund.entity.Refund> refundPage = refundRepository.findRefundsByArtist(
                artistId,
                refundStatus,
                request.keyword(),
                PageRequest.of(request.page(), request.size())
        );

        // Entity → DTO 변환
        List<ArtistCancellationResponse.CancellationRequest> content = refundPage.getContent().stream()
                .map(this::convertToCancellationDto)
                .toList();

        // 메모리에서 정렬 (간단한 정렬만 지원)
        if (request.sort() != null && !request.sort().equals("requestDate")) {
            content = sortInMemory(content, request.sort(), request.order());
        }

        int totalPages = refundPage.getTotalPages();
        long totalElements = refundPage.getTotalElements();
        boolean hasNext = refundPage.hasNext();
        boolean hasPrevious = refundPage.hasPrevious();

        log.info("작가 취소 요청 목록 조회 완료 - 조회된 요청 수: {}, 전체: {}", content.size(), totalElements);

        return new ArtistCancellationResponse.List(
                null,  // summary는 필요 없음
                content,
                request.page(),
                request.size(),
                totalElements,
                totalPages,
                hasNext,
                hasPrevious
        );
    }

    /**
     * 메모리에서 정렬 처리
     */
    private List<ArtistCancellationResponse.CancellationRequest> sortInMemory(
            List<ArtistCancellationResponse.CancellationRequest> list, String sort, String order) {

        boolean asc = "ASC".equalsIgnoreCase(order);

        return list.stream()
                .sorted((a, b) -> {
                    int cmp = 0;
                    switch (sort) {
                        case "productName":
                            String nameA = a.orderItem() != null ? a.orderItem().productName() : "";
                            String nameB = b.orderItem() != null ? b.orderItem().productName() : "";
                            cmp = nameA.compareTo(nameB);
                            break;
                        case "customerName":
                            String customerA = a.customer() != null ? a.customer().nickname() : "";
                            String customerB = b.customer() != null ? b.customer().nickname() : "";
                            cmp = customerA.compareTo(customerB);
                            break;
                        case "status":
                            cmp = a.status().compareTo(b.status());
                            break;
                        default:
                            cmp = a.requestDate().compareTo(b.requestDate());
                    }
                    return asc ? cmp : -cmp;
                })
                .toList();
    }

    /**
     * Refund 엔티티를 CancellationRequest DTO로 변환
     */
    private ArtistCancellationResponse.CancellationRequest convertToCancellationDto(
            com.back.domain.order.refund.entity.Refund refund) {

        // 주문 정보
        com.back.domain.order.order.entity.Order order = refund.getOrder();

        // 첫 번째 환불 아이템 가져오기 (주문 상품 정보용)
        com.back.domain.order.refund.entity.RefundItem firstRefundItem = refund.getRefundItems().isEmpty()
                ? null
                : refund.getRefundItems().get(0);

        // 주문 상품 정보
        ArtistCancellationResponse.OrderItem orderItemDto = null;
        if (firstRefundItem != null) {
            com.back.domain.order.orderItem.entity.OrderItem orderItem = firstRefundItem.getOrderItem();
            com.back.domain.product.product.entity.Product product = orderItem.getProduct();

            orderItemDto = new ArtistCancellationResponse.OrderItem(
                    product.getId(),
                    product.getName(),
                    firstRefundItem.getQuantity(),
                    firstRefundItem.getRefundPrice().intValue()
            );
        }

        // 고객 정보
        com.back.domain.user.entity.User customer = refund.getUser();
        ArtistCancellationResponse.Customer customerDto = new ArtistCancellationResponse.Customer(
                customer.getId(),
                customer.getName()
        );

        // 상태 텍스트 변환
        String statusText = convertRefundStatusToKorean(refund.getStatus());

        // 권한 정보 (작가 본인이므로 모든 권한 true, 단 이미 처리된 건은 false)
        boolean canApprove = refund.getStatus() == com.back.domain.order.refund.entity.Refund.RefundStatus.REQUESTED;
        boolean canReject = refund.getStatus() == com.back.domain.order.refund.entity.Refund.RefundStatus.REQUESTED;

        ArtistCancellationResponse.Permissions permissions = new ArtistCancellationResponse.Permissions(
                canApprove,
                canReject
        );

        return new ArtistCancellationResponse.CancellationRequest(
                refund.getId(),
                order.getId().toString(),
                order.getOrderNumber(),
                "CANCEL", // 또는 refund 타입에 따라 "REFUND"
                refund.getStatus().name(),
                statusText,
                refund.getCreateDate().atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                refund.getReason(),
                refund.getDetailReason() != null ? refund.getDetailReason() : "",
                customerDto,
                orderItemDto,
                refund.getRefundAmount().intValue(),
                permissions
        );
    }

    /**
     * RefundStatus Enum을 한글로 변환
     */
    private String convertRefundStatusToKorean(com.back.domain.order.refund.entity.Refund.RefundStatus status) {
        return switch (status) {
            case REQUESTED -> "처리대기";
            case COMPLETED -> "승인됨";
        };
    }

    @Override
    public ArtistExchangeResponse.List getExchangeRequests(Long artistId, ArtistExchangeSearchRequest request) {
        log.info("작가 교환 요청 목록 조회 시작 - artistId: {}, page: {}, size: {}, status: {}, keyword: {}, sort: {}, order: {}",
                artistId, request.page(), request.size(), request.status(), request.keyword(), request.sort(), request.order());

        // status 문자열을 ExchangeStatus enum으로 변환
        com.back.domain.order.exchange.entity.Exchange.ExchangeStatus exchangeStatus = null;
        if (request.status() != null && !request.status().isBlank()) {
            try {
                // PENDING, APPROVED 등을 REQUESTED, COMPLETED로 매핑
                exchangeStatus = switch (request.status()) {
                    case "PENDING" -> com.back.domain.order.exchange.entity.Exchange.ExchangeStatus.REQUESTED;
                    case "APPROVED" -> com.back.domain.order.exchange.entity.Exchange.ExchangeStatus.COMPLETED;
                    default -> com.back.domain.order.exchange.entity.Exchange.ExchangeStatus.valueOf(request.status());
                };
            } catch (IllegalArgumentException e) {
                log.warn("잘못된 교환 요청 상태값: {}", request.status());
            }
        }

        // 정렬 방향
        String sortOrder = request.order() != null ? request.order() : "DESC";
        
        // 정렬 필드에 따라 적절한 Repository 메서드 호출
        Page<com.back.domain.order.exchange.entity.Exchange> exchangePage;
        PageRequest pageRequest = PageRequest.of(request.page(), request.size());

        String sortField = request.sort() != null ? request.sort() : "requestDate";

        switch (sortField) {
            case "productName" -> {
                // 상품명 정렬 (DB 쿼리)
                if ("ASC".equalsIgnoreCase(sortOrder)) {
                    exchangePage = exchangeRepository.findExchangesByArtistSortedByProductNameAsc(
                            artistId, exchangeStatus, request.keyword(), pageRequest);
                } else {
                    exchangePage = exchangeRepository.findExchangesByArtistSortedByProductNameDesc(
                            artistId, exchangeStatus, request.keyword(), pageRequest);
                }
            }
            case "customerName" -> {
                // 구매자 이름 정렬 (DB 쿼리)
                if ("ASC".equalsIgnoreCase(sortOrder)) {
                    exchangePage = exchangeRepository.findExchangesByArtistSortedByCustomerNameAsc(
                            artistId, exchangeStatus, request.keyword(), pageRequest);
                } else {
                    exchangePage = exchangeRepository.findExchangesByArtistSortedByCustomerNameDesc(
                            artistId, exchangeStatus, request.keyword(), pageRequest);
                }
            }
            default -> {
                // 주문일자, 상태 정렬 (Pageable Sort 사용)
                org.springframework.data.domain.Sort sort = createExchangeSort(sortField, sortOrder);
                pageRequest = PageRequest.of(request.page(), request.size(), sort);
                exchangePage = exchangeRepository.findExchangesByArtist(
                        artistId, exchangeStatus, request.keyword(), pageRequest);
            }
        }

        // Entity → DTO 변환
        List<ArtistExchangeResponse.ExchangeRequest> content = exchangePage.getContent().stream()
                .map(this::convertToExchangeDto)
                .toList();

        int totalPages = exchangePage.getTotalPages();
        long totalElements = exchangePage.getTotalElements();
        boolean hasNext = exchangePage.hasNext();
        boolean hasPrevious = exchangePage.hasPrevious();

        log.info("작가 교환 요청 목록 조회 완료 - 조회된 요청 수: {}, 전체: {}", content.size(), totalElements);

        return new ArtistExchangeResponse.List(
                null,  // summary는 필요 없음
                content,
                request.page(),
                request.size(),
                totalElements,
                totalPages,
                hasNext,
                hasPrevious
        );
    }

    /**
     * 교환 요청 정렬 생성 (requestDate, status 정렬용)
     */
    private org.springframework.data.domain.Sort createExchangeSort(String sortField, String sortOrder) {
        org.springframework.data.domain.Sort.Direction direction =
                "ASC".equalsIgnoreCase(sortOrder)
                        ? org.springframework.data.domain.Sort.Direction.ASC
                        : org.springframework.data.domain.Sort.Direction.DESC;

        return switch (sortField) {
            case "status" -> org.springframework.data.domain.Sort.by(direction, "status");
            default -> org.springframework.data.domain.Sort.by(direction, "createDate"); // requestDate
        };
    }

    /**
     * Exchange 엔티티를 ExchangeRequest DTO로 변환
     */
    private ArtistExchangeResponse.ExchangeRequest convertToExchangeDto(
            com.back.domain.order.exchange.entity.Exchange exchange) {

        // 주문 정보
        com.back.domain.order.order.entity.Order order = exchange.getOrder();

        // 첫 번째 교환 아이템 가져오기 (주문 상품 정보용)
        com.back.domain.order.exchange.entity.ExchangeItem firstExchangeItem = exchange.getExchangeItems().isEmpty()
                ? null
                : exchange.getExchangeItems().get(0);

        // 주문 상품 정보
        ArtistExchangeResponse.OrderItem orderItemDto = null;
        if (firstExchangeItem != null) {
            com.back.domain.order.orderItem.entity.OrderItem orderItem = firstExchangeItem.getOrderItem();
            com.back.domain.product.product.entity.Product product = orderItem.getProduct();

            orderItemDto = new ArtistExchangeResponse.OrderItem(
                    product.getId(),
                    product.getName(),
                    firstExchangeItem.getQuantity(),
                    orderItem.getPrice().intValue()
            );
        }

        // 고객 정보
        com.back.domain.user.entity.User customer = exchange.getUser();
        ArtistExchangeResponse.Customer customerDto = new ArtistExchangeResponse.Customer(
                customer.getId(),
                customer.getName()
        );

        int exchangeQuantity = firstExchangeItem != null ? firstExchangeItem.getQuantity() : 0;

        ArtistExchangeResponse.ExchangeRequested exchangeRequested = new ArtistExchangeResponse.ExchangeRequested(
                "",  // 교환 방법은 상세 조회에서만 제공 예정
                exchangeQuantity
        );

        // 상태 텍스트 변환
        String statusText = convertExchangeStatusToKorean(exchange.getStatus());
        String status = convertExchangeStatusToApi(exchange.getStatus());

        // 권한 정보 (작가 본인이므로 모든 권한 true, 단 이미 처리된 건은 false)
        boolean canApprove = exchange.getStatus() == com.back.domain.order.exchange.entity.Exchange.ExchangeStatus.REQUESTED;
        boolean canReject = exchange.getStatus() == com.back.domain.order.exchange.entity.Exchange.ExchangeStatus.REQUESTED;

        ArtistExchangeResponse.Permissions permissions = new ArtistExchangeResponse.Permissions(
                canApprove,
                canReject
        );

        return new ArtistExchangeResponse.ExchangeRequest(
                exchange.getId(),
                order.getId().toString(),
                order.getOrderNumber(),
                "EXCHANGE",
                status,
                statusText,
                exchange.getCreateDate().atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                exchange.getReason(),
                exchange.getDetailReason() != null ? exchange.getDetailReason() : "",
                customerDto,
                orderItemDto,
                exchangeRequested,
                permissions
        );
    }

    /**
     * ExchangeStatus Enum을 한글로 변환
     */
    private String convertExchangeStatusToKorean(com.back.domain.order.exchange.entity.Exchange.ExchangeStatus status) {
        return switch (status) {
            case REQUESTED -> "처리대기";
            case COMPLETED -> "승인됨";
        };
    }

    /**
     * ExchangeStatus Enum을 API 상태값으로 변환
     */
    private String convertExchangeStatusToApi(com.back.domain.order.exchange.entity.Exchange.ExchangeStatus status) {
        return switch (status) {
            case REQUESTED -> "PENDING";
            case COMPLETED -> "APPROVED";
        };
    }


    @Override
    public ArtistSettingsResponse getSettings(Long artistId) {
        log.info("작가 설정 정보 조회 - artistId: {}", artistId);

        // 실제 DB에서 작가 프로필 조회
        com.back.domain.artist.entity.ArtistProfile artistProfile = artistProfileRepository
                .findByUserId(artistId)
                .orElseThrow(() -> new com.back.global.exception.ServiceException("404", "작가 프로필을 찾을 수 없습니다."));

        log.info("작가 프로필 조회 완료 - artistId: {}, artistName: {}", artistId, artistProfile.getArtistName());

        // Entity → DTO 변환
        return convertToSettingsResponse(artistProfile);
    }

    /**
     * ArtistProfile 엔티티를 ArtistSettingsResponse DTO로 변환
     */
    private ArtistSettingsResponse convertToSettingsResponse(com.back.domain.artist.entity.ArtistProfile profile) {
        // 프로필 정보
        ArtistSettingsResponse.Profile profileDto = new ArtistSettingsResponse.Profile(
                profile.getArtistName(),
                profile.getDescription() != null ? profile.getDescription() : "",
                profile.getSnsAccount() != null ?
                        List.of(new ArtistSettingsResponse.Sns("Instagram", profile.getSnsAccount())) :
                        List.of(),
                profile.getProfileImageUrl()
        );

        // 사업자 정보
        String fullAddress = buildFullAddress(
                profile.getBusinessAddress(),
                profile.getBusinessAddressDetail()
        );

        ArtistSettingsResponse.Business business = new ArtistSettingsResponse.Business(
                fullAddress,
                null,  // 사업자등록번호 (ArtistProfile에 없음)
                null,  // 통신판매업신고번호 (ArtistProfile에 없음)
                profile.getBusinessAddress() != null  // 사업자 주소가 있으면 인증됨으로 간주
        );

        // 정산 계좌 정보 (마스킹)
        ArtistSettingsResponse.Payout payout = new ArtistSettingsResponse.Payout(
                null,  // 은행 코드 (ArtistProfile에 없음)
                profile.getBankName(),
                profile.getAccountName(),
                maskAccountNumber(profile.getBankAccount()),
                profile.getBankAccount() != null ? "VERIFIED" : "PENDING"
        );

        // 권한 정보 (작가 본인이므로 모든 권한 true)
        ArtistSettingsResponse.Permissions permissions = new ArtistSettingsResponse.Permissions(
                true,  // canEditProfile
                true,  // canEditBusiness
                true   // canEditPayout
        );

        return new ArtistSettingsResponse(profileDto, business, payout, permissions);
    }

    /**
     * 주소와 상세주소를 합쳐서 전체 주소 생성
     */
    private String buildFullAddress(String address, String detailAddress) {
        if (address == null) {
            return null;
        }
        if (detailAddress == null || detailAddress.isBlank()) {
            return address;
        }
        return address + " " + detailAddress;
    }

    /**
     * 계좌번호 마스킹 처리
     * 예: "123-456-789012" → "****-****-**9012"
     */
    private String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.isBlank()) {
            return null;
        }

        // 계좌번호가 4자리 미만이면 전체 마스킹
        if (accountNumber.length() < 4) {
            return "****";
        }

        // 마지막 4자리만 보이고 나머지는 마스킹
        String lastFour = accountNumber.substring(accountNumber.length() - 4);
        return "****-****-**" + lastFour;
    }

    @Override
    public ArtistFundingResponse.List getFundings(Long artistId, ArtistFundingSearchRequest request) {
        log.info("작가 펀딩 목록 조회 시작 - artistId: {}, page: {}, size: {}, keyword: {}, status: {}, sort: {}, order: {}",
                artistId, request.page(), request.size(), request.keyword(), request.status(), request.sort(), request.order());

        // status 문자열을 FundingStatus enum으로 변환
        FundingStatus fundingStatus = null;
        if (request.status() != null && !request.status().isBlank()) {
            try {
                fundingStatus = FundingStatus.valueOf(request.status());
            } catch (IllegalArgumentException e) {
                log.warn("잘못된 펀딩 상태값: {}", request.status());
            }
        }

        // Repository를 통한 실제 DB 조회
        Page<Funding> fundingPage = fundingRepository.findFundingsByArtist(
                artistId, request.keyword(), fundingStatus, request.sort(), request.order(),
                PageRequest.of(request.page(), request.size())
        );

        // Entity → DTO 변환
        List<ArtistFundingResponse.Funding> content = fundingPage.getContent().stream()
                .map(this::convertToFundingDto)
                .toList();

        int totalPages = fundingPage.getTotalPages();
        long totalElements = fundingPage.getTotalElements();
        boolean hasNext = fundingPage.hasNext();
        boolean hasPrevious = fundingPage.hasPrevious();

        log.info("작가 펀딩 목록 조회 완료 - 조회된 펀딩 수: {}, 전체: {}", content.size(), totalElements);

        return new ArtistFundingResponse.List(
                content,
                request.page(), request.size(), totalElements, totalPages, hasNext, hasPrevious
        );
    }

    /**
     * Funding 엔티티를 DTO로 변환
     */
    private ArtistFundingResponse.Funding convertToFundingDto(Funding funding) {
        // 누적 모금액 조회
        long currentAmount = nz(fundingContributionRepository.sumContributedAmountByFundingId(funding.getId()));

        // 참여자 수 조회 (Funding 엔티티에 저장된 값 사용)
        int participantCount = funding.getParticipantCount();

        // 달성률 계산
        double achievementRate = funding.getTargetAmount() == 0
                ? 0.0
                : Math.min(100.0, (currentAmount * 100.0) / funding.getTargetAmount());

        // 남은 일수 계산
        int remainingDays = 0;
        boolean dueSoon = false;
        boolean ended = false;

        if (funding.getEndDate() != null) {
            remainingDays = (int) ChronoUnit.DAYS.between(LocalDate.now(), funding.getEndDate().toLocalDate());
            dueSoon = remainingDays > 0 && remainingDays <= 7;
            ended = remainingDays < 0;
        }

        // 목표 달성 여부
        boolean goalAchieved = currentAmount >= funding.getTargetAmount();

        // 권한 정보 (작가 본인이므로 모든 권한 true)
        ArtistFundingResponse.Permissions permissions = new ArtistFundingResponse.Permissions(
                true,  // canEdit
                funding.getStatus() == FundingStatus.OPEN,  // canCancel (진행중일 때만)
                true   // canCreateNews
        );

        // 플래그 정보
        ArtistFundingResponse.Flags flags = new ArtistFundingResponse.Flags(
                goalAchieved,
                dueSoon,
                ended
        );

        // 카테고리 정보 (현재 미사용)
        ArtistFundingResponse.Category category = null;

        return new ArtistFundingResponse.Funding(
                funding.getId(),
                funding.getTitle(),
                funding.getStatus().name(),
                convertFundingStatusToKorean(funding.getStatus()),
                funding.getTargetAmount(),
                currentAmount,
                achievementRate,
                participantCount,
                funding.getStartDate().format(DATE_FORMATTER),
                funding.getEndDate().format(DATE_FORMATTER),
                funding.getCreateDate().format(DATE_FORMATTER),
                funding.getImageUrl(),
                category,
                permissions,
                flags
        );
    }

    /**
     * FundingStatus Enum을 한글로 변환
     */
    private String convertFundingStatusToKorean(FundingStatus status) {
        return switch (status) {
            case PENDING -> "심사중";
            case APPROVED -> "승인됨";
            case REJECTED -> "거절됨";
            case OPEN -> "진행중";
            case CLOSED -> "마감";
            case SUCCESS -> "성공";
            case FAILED -> "실패";
            case CANCELED -> "취소됨";
        };
    }

    /**
     * null을 0으로 변환하는 헬퍼 메서드
     */
    private long nz(Long value) {
        return value == null ? 0L : value;
    }

    @Override
    public ArtistSettlementResponse getSettlements(Long artistId, ArtistSettlementSearchRequest request) {
        log.info("작가 정산 내역 조회 시작 - artistId: {}, year: {}, month: {}, page: {}, size: {}, sort: {}, order: {}",
                artistId, request.year(), request.month(), request.page(), request.size(), request.sort(), request.order());

        // 1. 작가 조회
        com.back.domain.user.entity.User artist = userRepository.findById(artistId)
                .orElseThrow(() -> new com.back.global.exception.ServiceException("404", "작가를 찾을 수 없습니다."));

        // 2. 조회 범위 설정
        Integer year = request.year() != null ? request.year() : LocalDate.now().getYear();
        Integer month = request.month(); // null이면 전체 연도

        ArtistSettlementResponse.Scope scope = new ArtistSettlementResponse.Scope(year, month);

        // 3. 기간 계산
        LocalDateTime startDate;
        LocalDateTime endDate;

        if (month != null) {
            // 특정 월
            startDate = LocalDateTime.of(year, month, 1, 0, 0, 0);
            endDate = startDate.plusMonths(1).minusSeconds(1);
        } else {
            // 연도 전체
            startDate = LocalDateTime.of(year, 1, 1, 0, 0, 0);
            endDate = LocalDateTime.of(year, 12, 31, 23, 59, 59);
        }

        // 4. 요약 정보 조회 (배송 완료된 주문에서 계산)
        List<com.back.domain.order.order.entity.Order> deliveredOrders =
                orderRepository.findDeliveredOrdersByArtistInPeriod(artist, startDate, endDate);

        // 작가의 상품 매출만 계산
        int totalSales = deliveredOrders.stream()
                .flatMap(order -> order.getOrderItems().stream())
                .filter(item -> item.getProduct().getUser().getId().equals(artistId))
                .mapToInt(item -> item.getPrice().intValue() * item.getQuantity())
                .sum();

        int totalCommission = totalSales / 10;  // 10% 수수료
        int totalNetIncome = totalSales - totalCommission;

        ArtistSettlementResponse.Summary summary = new ArtistSettlementResponse.Summary(
                new ArtistSettlementResponse.AmountInfo(totalSales, "총 매출"),
                new ArtistSettlementResponse.AmountInfo(totalCommission, "수수료"),
                new ArtistSettlementResponse.AmountInfo(totalNetIncome, "순수익")
        );

        // 5. 차트 데이터 (월별 집계)
        ArtistSettlementResponse.Chart chart = createSettlementChartFromOrders(artistId, year, month);

        // 6. 테이블 데이터 (정산 내역 목록)
        ArtistSettlementResponse.Table table = createSettlementTableFromOrders(
                artist, startDate, endDate, request, artistId
        );

        log.info("작가 정산 내역 조회 완료 - artistId: {}, 총매출: {}, 수수료: {}, 순수익: {}",
                artistId, totalSales, totalCommission, totalNetIncome);

        return new ArtistSettlementResponse(
                scope,
                "Asia/Seoul",
                summary,
                chart,
                table,
                LocalDateTime.now()
        );
    }

    /**
     * 정산 차트 데이터 생성 (월별 매출 그래프 - 1월~12월)
     * Order 데이터에서 직접 조회
     */
    private ArtistSettlementResponse.Chart createSettlementChartFromOrders(Long artistId, Integer year, Integer month) {
        List<ArtistSettlementResponse.ChartDataPoint> salesPoints = new ArrayList<>();

        // 작가 조회
        com.back.domain.user.entity.User artist = userRepository.findById(artistId)
                .orElseThrow(() -> new com.back.global.exception.ServiceException("404", "작가를 찾을 수 없습니다."));

        // 항상 연도 전체의 월별 데이터 (1월~12월)
        for (int m = 1; m <= 12; m++) {
            LocalDateTime monthStart = LocalDateTime.of(year, m, 1, 0, 0, 0);
            LocalDateTime monthEnd = monthStart.plusMonths(1).minusSeconds(1);

            // 해당 월의 배송 완료된 주문 조회
            List<com.back.domain.order.order.entity.Order> monthOrders =
                    orderRepository.findDeliveredOrdersByArtistInPeriod(artist, monthStart, monthEnd);

            // 작가의 상품 매출만 계산
            int monthTotal = monthOrders.stream()
                    .flatMap(order -> order.getOrderItems().stream())
                    .filter(item -> item.getProduct().getUser().getId().equals(artistId))
                    .mapToInt(item -> item.getPrice().intValue() * item.getQuantity())
                    .sum();

            salesPoints.add(new ArtistSettlementResponse.ChartDataPoint(
                    String.format("%d-%02d", year, m),
                    monthTotal
            ));
        }

        // Y축 범위 계산
        int maxValue = salesPoints.stream()
                .mapToInt(ArtistSettlementResponse.ChartDataPoint::value)
                .max()
                .orElse(0);

        int yMax = (int) Math.ceil(maxValue * 1.2); // 최대값의 120%

        ArtistSettlementResponse.ChartSeries series = new ArtistSettlementResponse.ChartSeries(salesPoints);
        ArtistSettlementResponse.YDomain yDomain = new ArtistSettlementResponse.YDomain(0, yMax);

        return new ArtistSettlementResponse.Chart(series, yDomain);
    }

    /**
     * 정산 테이블 데이터 생성 (Order 기반)
     */
    private ArtistSettlementResponse.Table createSettlementTableFromOrders(
            com.back.domain.user.entity.User artist,
            LocalDateTime startDate,
            LocalDateTime endDate,
            ArtistSettlementSearchRequest request,
            Long artistId) {

        // 1. 정렬 필드 확인
        String sortField = request.sort() != null ? request.sort() : "date";
        String sortOrder = request.order() != null ? request.order() : "DESC";
        boolean isProductNameSort = "productName".equals(sortField);

        // 2. 배송 완료된 주문 조회
        List<com.back.domain.order.order.entity.Order> allOrders;
        
        if (isProductNameSort) {
            // 상품명 정렬: DB에서 정렬된 상태로 조회
            allOrders = orderRepository.findDeliveredOrdersByArtistInPeriodSortedByProductName(
                    artist, startDate, endDate, sortOrder);
        } else {
            // 일반 조회 (정렬은 메모리에서 처리)
            allOrders = orderRepository.findDeliveredOrdersByArtistInPeriod(
                    artist, startDate, endDate);
        }

        // 3. 주문을 OrderItem 단위로 변환 (작가의 상품만)
        List<SettlementOrderItem> settlementItems = allOrders.stream()
                .flatMap(order -> order.getOrderItems().stream()
                        .filter(item -> item.getProduct().getUser().getId().equals(artistId))
                        .map(item -> new SettlementOrderItem(
                                order.getId(),
                                order.getOrderDate(),
                                item.getProduct().getId(),
                                item.getProduct().getName(),
                                item.getPrice().intValue() * item.getQuantity()
                        )))
                .toList();

        // 4. 정렬 적용 (상품명 정렬이 아닌 경우만 메모리 정렬)
        if (!isProductNameSort) {
            settlementItems = sortSettlementItems(settlementItems, sortField, sortOrder);
        }

        // 5. 페이징 처리
        int start = request.page() * request.size();
        int end = Math.min(start + request.size(), settlementItems.size());
        List<SettlementOrderItem> pagedItems = start < settlementItems.size()
                ? settlementItems.subList(start, end)
                : List.of();

        // 6. DTO 변환
        List<ArtistSettlementResponse.Settlement> content = pagedItems.stream()
                .map(this::convertToSettlementDtoFromOrder)
                .toList();

        int totalElements = settlementItems.size();
        int totalPages = (int) Math.ceil((double) totalElements / request.size());
        boolean hasNext = request.page() < totalPages - 1;
        boolean hasPrevious = request.page() > 0;

        return new ArtistSettlementResponse.Table(
                content,
                request.page(),
                request.size(),
                totalElements,
                totalPages,
                hasNext,
                hasPrevious
        );
    }

    /**
     * 정산 아이템 정렬 (메모리 정렬)
     * productName 정렬은 DB에서 처리하므로 여기서는 제외
     */
    private List<SettlementOrderItem> sortSettlementItems(
            List<SettlementOrderItem> items, String sortField, String sortOrder) {

        boolean asc = "ASC".equalsIgnoreCase(sortOrder);

        return items.stream()
                .sorted((a, b) -> {
                    int cmp = switch (sortField) {
                        case "productName" -> a.productName.compareTo(b.productName);
                        case "grossAmount" -> Integer.compare(a.grossAmount, b.grossAmount);
                        case "commission" -> Integer.compare(a.grossAmount / 10, b.grossAmount / 10);
                        case "netAmount" -> Integer.compare(a.grossAmount * 9 / 10, b.grossAmount * 9 / 10);
                        default -> a.orderDate.compareTo(b.orderDate);
                    };
                    return asc ? cmp : -cmp;
                })
                .toList();
    }

    /**
     * Order 기반으로 Settlement DTO 변환
     */
    private ArtistSettlementResponse.Settlement convertToSettlementDtoFromOrder(
            SettlementOrderItem item) {

        // 상품 정보
        ArtistSettlementResponse.Product product = new ArtistSettlementResponse.Product(
                item.productId,
                item.productName
        );

        // 날짜 포맷팅
        String dateStr = item.orderDate.format(DateTimeFormatter.ofPattern("yyyy. MM. dd"));

        // 금액 계산
        int grossAmount = item.grossAmount;
        int commission = grossAmount / 10;  // 10% 수수료
        int netAmount = grossAmount - commission;

        // 배송 완료된 주문이므로 항상 정산완료 상태
        String statusText = "정산완료";

        return new ArtistSettlementResponse.Settlement(
                item.orderId,
                dateStr,
                product,
                grossAmount,
                commission,
                netAmount,
                "COMPLETED",
                statusText
        );
    }

    /**
     * 정산용 주문 아이템 임시 클래스
     */
    private record SettlementOrderItem(
            Long orderId,
            LocalDateTime orderDate,
            Long productId,
            String productName,
            int grossAmount
    ) {
    }


    @Override
    public ArtistTrafficSourceResponse getTrafficSources(Long artistId, int days, String timezone) {
        log.info("작가 유입 경로 조회 - artistId: {}, days: {}, timezone: {}", artistId, days, timezone);

        try {
            // GA4 API 요청 생성 (특정 작가 필터링)
            String campaignFilter = "artist_" + artistId;

            RunReportRequest request = RunReportRequest.newBuilder()
                    .setProperty(propertyId)
                    // 측정 기준: 세션 소스 (Instagram, YouTube 등)
                    .addDimensions(Dimension.newBuilder().setName("sessionSource"))
                    // 지표: 세션 수, 사용자 수, 신규 사용자, 전환 수
                    .addMetrics(Metric.newBuilder().setName("sessions"))
                    .addMetrics(Metric.newBuilder().setName("totalUsers"))
                    .addMetrics(Metric.newBuilder().setName("newUsers"))
                    .addMetrics(Metric.newBuilder().setName("conversions"))
                    // 조회 기간 설정
                    .addDateRanges(DateRange.newBuilder()
                            .setStartDate(days + "daysAgo")
                            .setEndDate("today"))
                    // 특정 작가의 캠페인만 필터링
                    .setDimensionFilter(
                            FilterExpression.newBuilder()
                                    .setFilter(Filter.newBuilder()
                                            .setFieldName("sessionCampaignName")
                                            .setStringFilter(Filter.StringFilter.newBuilder()
                                                    .setMatchType(Filter.StringFilter.MatchType.CONTAINS)
                                                    .setValue(campaignFilter)
                                            )
                                    )
                    )
                    // 세션 수 기준 내림차순 정렬
                    .addOrderBys(OrderBy.newBuilder()
                            .setMetric(OrderBy.MetricOrderBy.newBuilder()
                                    .setMetricName("sessions"))
                            .setDesc(true))
                    .build();

            // GA4 API 호출
            RunReportResponse response = analyticsDataClient.runReport(request);

            // 총합 계산
            long totalSessions = 0;
            long totalUsers = 0;
            long totalNewUsers = 0;
            long totalConversions = 0;

            List<ArtistTrafficSourceResponse.Source> sources = new ArrayList<>();
            List<ArtistTrafficSourceResponse.ChartData> chartData = new ArrayList<>();

            // 색상 팔레트 (소셜 미디어별)
            java.util.Map<String, String> colorMap = java.util.Map.of(
                    "instagram", "#E4405F",
                    "youtube", "#FF0000",
                    "naver", "#03C75A",
                    "google", "#4285F4",
                    "facebook", "#1877F2",
                    "twitter", "#1DA1F2",
                    "kakao", "#FEE500"
            );

            // 응답 데이터 처리
            for (Row row : response.getRowsList()) {
                String sourceName = row.getDimensionValues(0).getValue();
                long sessions = Long.parseLong(row.getMetricValues(0).getValue());
                long users = Long.parseLong(row.getMetricValues(1).getValue());
                long newUsers = Long.parseLong(row.getMetricValues(2).getValue());
                long conversions = Long.parseLong(row.getMetricValues(3).getValue());

                totalSessions += sessions;
                totalUsers += users;
                totalNewUsers += newUsers;
                totalConversions += conversions;

                // 색상 선택 (소문자로 매칭)
                String color = colorMap.getOrDefault(sourceName.toLowerCase(), "#999999");

                sources.add(new ArtistTrafficSourceResponse.Source(
                        sourceName,
                        sessions,
                        users,
                        0.0, // 점유율은 나중에 계산
                        newUsers,
                        users > 0 ? (double) newUsers / users * 100 : 0.0,
                        conversions,
                        sessions > 0 ? (double) conversions / sessions * 100 : 0.0
                ));

                chartData.add(new ArtistTrafficSourceResponse.ChartData(
                        sourceName,
                        sessions,
                        0.0, // 퍼센트는 나중에 계산
                        color
                ));
            }

            // 점유율 계산 (총 세션 대비)
            final long finalTotalSessions = totalSessions;
            sources = sources.stream()
                    .map(source -> new ArtistTrafficSourceResponse.Source(
                            source.name(),
                            source.sessions(),
                            source.users(),
                            finalTotalSessions > 0 ? (double) source.sessions() / finalTotalSessions * 100 : 0.0,
                            source.newUsers(),
                            source.newUserRate(),
                            source.conversions(),
                            source.conversionRate()
                    ))
                    .toList();

            chartData = chartData.stream()
                    .map(data -> new ArtistTrafficSourceResponse.ChartData(
                            data.name(),
                            data.value(),
                            finalTotalSessions > 0 ? (double) data.value() / finalTotalSessions * 100 : 0.0,
                            data.color()
                    ))
                    .toList();

            // 가장 많은 유입 경로
            String topSource = sources.isEmpty() ? "없음" : sources.get(0).name();

            // 전환율 계산
            double conversionRate = totalSessions > 0
                    ? (double) totalConversions / totalSessions * 100
                    : 0.0;

            // 요약 정보
            ArtistTrafficSourceResponse.Summary summary = new ArtistTrafficSourceResponse.Summary(
                    totalSessions,
                    totalUsers,
                    totalConversions,
                    conversionRate,
                    topSource
            );

            // 차트 데이터
            ArtistTrafficSourceResponse.Chart chart = new ArtistTrafficSourceResponse.Chart(
                    chartData,
                    5.0 // 5% 미만은 "기타"로 그룹화
            );

            // 조회 기간
            LocalDate endDate = LocalDate.now();
            LocalDate startDate = endDate.minusDays(days);
            ArtistTrafficSourceResponse.Period period = new ArtistTrafficSourceResponse.Period(
                    startDate.toString(),
                    endDate.toString(),
                    days
            );

            log.info("작가 유입 경로 조회 완료 - artistId: {}, 총 {}개 소스, 총 세션 {}",
                    artistId, sources.size(), totalSessions);

            return new ArtistTrafficSourceResponse(
                    summary,
                    sources,
                    chart,
                    period,
                    LocalDateTime.now(),
                    timezone
            );

        } catch (Exception e) {
            log.error("작가 유입 경로 조회 중 오류 발생 - artistId: {}", artistId, e);

            // 오류 발생 시 빈 데이터 반환
            return new ArtistTrafficSourceResponse(
                    new ArtistTrafficSourceResponse.Summary(0, 0, 0, 0.0, "없음"),
                    List.of(),
                    new ArtistTrafficSourceResponse.Chart(List.of(), 5.0),
                    new ArtistTrafficSourceResponse.Period(
                            LocalDate.now().minusDays(days).toString(),
                            LocalDate.now().toString(),
                            days
                    ),
                    LocalDateTime.now(),
                    timezone
            );
        }
    }

    /**
     * Follow 엔티티에서 직접 일별 팔로워 수 계산
     * - 기존 findFollowersByArtistId() 메서드만 사용
     * - 다른 팀원 코드 수정 불필요
     * <p>
     * ⚠️ 주의: 모든 Follow를 메모리에 로드하므로
     * 팔로워가 매우 많을 경우 성능 이슈 가능 (추후 최적화 권장)
     */
    private ArtistMainResponse.SeriesData createFollowerSeriesFromFollowEntities(
            Long artistProfileId, LocalDate startDate, LocalDate endDate) {

        // 해당 작가의 모든 팔로우 관계 조회 (기존 메서드 활용)
        List<Follow> allFollows = followRepository.findFollowersByArtistId(artistProfileId);

        List<ArtistMainResponse.DataPoint> points = new ArrayList<>();

        // 일별로 루프
        LocalDate current = startDate;
        while (!current.isAfter(endDate)) {
            final LocalDateTime endOfDay = current.atTime(23, 59, 59);

            // 해당 날짜까지 생성된 팔로우의 개수
            long count = allFollows.stream()
                    .filter(f -> !f.getCreateDate().isAfter(endOfDay))
                    .count();

            points.add(new ArtistMainResponse.DataPoint(
                    current.toString(),
                    (int) count
            ));

            current = current.plusDays(1);
        }

        // 현재 팔로워 수 (기존 메서드 사용)
        int currentCount = (int) followRepository.countByFollowingArtistId(artistProfileId);

        return new ArtistMainResponse.SeriesData("명", points, currentCount);
    }
}