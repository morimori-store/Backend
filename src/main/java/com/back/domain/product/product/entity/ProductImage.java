package com.back.domain.product.product.entity;

import com.back.global.jpa.entity.BaseEntity;
import com.back.global.s3.FileType;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "product_images")
public class ProductImage extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional=false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product; // 상품 FK

    @Column(nullable = false)
    private String fileUrl; // S3에 업로드된 파일의 URL

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private FileType fileType; // 파일 종류 (MAIN, ADDITIONAL, THUMBNAIL, DOCUMENT)

    @Column(nullable = false)
    private String s3Key; // S3의 파일들을 식별할 수 있는 객체 키

    @Column(nullable = false)
    private String originalFilename; // 원본 파일명 (이미지는 저장 x, 문서의 경우 저장)
}
