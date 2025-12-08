package com.back.domain.product.product.entity;

import com.back.domain.product.tag.entity.Tag;
import com.back.global.jpa.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.Objects;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "product_tag_mappings", // 상품-태그 중간 테이블(N:M 관계라서 중간테이블이 필요함)
        uniqueConstraints = {@UniqueConstraint(columnNames = {"product_id", "tag_id"})}) // 하나의 상품에 같은 태그 중복 방지를 위해.
public class ProductTagMapping extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional=false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product; // 상품 FK

    @ManyToOne(fetch = FetchType.LAZY, optional=false)
    @JoinColumn(name = "tag_id", nullable = false)
    private Tag tag; // 태그 FK

    // 기존 id로 비교하면 동일 상품에 같은 태그가 중복 저장돼서 product+tag 조합으로 기준 변경
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProductTagMapping that)) return false;
        return Objects.equals(product, that.product) &&
                Objects.equals(tag, that.tag);
    }

    @Override
    public int hashCode() {
        return Objects.hash(product, tag);
    }
}
