package com.back.domain.product.qna.entity;

import com.back.domain.product.product.entity.Product;
import com.back.domain.user.entity.User;
import com.back.global.jpa.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Entity
@Table(name = "product_qna")
public class ProductQna extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional=false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product; // 상품 FK

    @ManyToOne(fetch = FetchType.LAZY, optional=false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user; // Q&A 작성자 FK

    @Column(nullable = false)
    private String qnaCategory; // 카테고리

    @Column(nullable = false)
    private String qnaTitle; // 제목

    @Column(columnDefinition = "TEXT", nullable = false)
    private String qnaDescription; // 내용

    @Builder.Default
    @OneToMany(mappedBy = "productQna", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ProductQnaImage> productQnaImages = new ArrayList<>();

    public void addProductQnaImage(ProductQnaImage productQnaImage) {
        productQnaImages.add(productQnaImage);
        productQnaImage.setProductQna(this);
    }
}
