package com.back.domain.product.qna.entity;

import com.back.global.jpa.entity.BaseEntity;
import com.back.global.s3.FileType;
import jakarta.persistence.*;
import lombok.*;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Entity
@Table(name = "product_qna_image")
public class ProductQnaImage extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional=false)
    @JoinColumn(name = "product_qna_id", nullable = false)
    private ProductQna productQna;

    @Column(nullable = false)
    private String fileUrl;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private FileType fileType;

    @Column(nullable = false)
    private String s3Key;

    @Column(nullable = false)
    private String originalFileName;
}