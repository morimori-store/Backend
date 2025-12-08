package com.back.domain.product.product.entity;

import com.back.global.jpa.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "options")
public class Option extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional=false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product; // 상품 FK

    @Column(nullable=false)
    private String optionName; // 옵션명

    @Column(nullable=false)
    private int optionAdditionalPrice; // 옵션별 추가 금액

    @Column(nullable=false)
    private int optionStock; //옵션별 재고
}
