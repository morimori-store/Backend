package com.back.domain.product.product.scheduler;

import com.back.domain.product.product.entity.Product;
import com.back.domain.product.product.entity.SellingStatus;
import com.back.domain.product.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class ProductSellingStatusScheduler {

    private final ProductRepository productRepository;

    // 매일 한국 시간 기준 00:00시에 실행
    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
    public void updateSellingStatus() {
        LocalDateTime todayStartKST = LocalDate.now(ZoneId.of("Asia/Seoul")).atStartOfDay();
        log.info("[판매 상태 스케줄러] 실행 - 오늘 날짜(KST): {}", todayStartKST.toLocalDate());

        // 판매 시작일이 오늘 이후인 상품의 판매 상태 -> SELLING_BEFORE(판매 전)
        List<Product> beforeSellingProducts = productRepository.findBySellingStartDateAfter(todayStartKST);
        beforeSellingProducts.forEach(product -> product.setSellingStatus(SellingStatus.BEFORE_SELLING));

        // 판매 종료일이 오늘 이전인 상품의 판매 상태 -> END_OF_SALE(판매 종료)
        List<Product> endedProducts = productRepository.findBySellingEndDateBefore(todayStartKST);
        endedProducts.forEach(product -> product.setSellingStatus(SellingStatus.END_OF_SALE));

        // DB에 반영
        productRepository.saveAll(beforeSellingProducts);
        productRepository.saveAll(endedProducts);

        log.info("[판매 상태 스케줄러] 완료 - SELLING_BEFORE: {}, END_OF_SALE: {}",
                beforeSellingProducts.size(), endedProducts.size());
    }
}
