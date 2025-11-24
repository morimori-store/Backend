package com.back.global.s3;

import com.back.global.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
@Service
public class S3Service {

    private final S3Client s3Client;
    private final S3Service self;

    @Value("${cloud.aws.s3.bucket}")
    private String bucketName;

    public S3Service(S3Client s3Client, @Lazy S3Service self) {
        this.s3Client = s3Client;
        this.self = self;
    }

    /**
     * s3에 파일 업로드 처리 메서드
     */
    public List<UploadResultResponse> uploadFiles(List<MultipartFile> files, String folder, List<FileType> types) {
        if (files == null || files.isEmpty()) {
            throw new ServiceException("400", "업로드할 파일이 없습니다.");
        }
        if (types == null || files.size() != types.size()) {
            throw new ServiceException("400", "files와 types의 개수가 일치하지 않습니다.");
        }

        List<CompletableFuture<List<UploadResultResponse>>> futures = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            FileType type = types.get(i);

            if (type == null) {
                throw new ServiceException("400", "파일 타입이 null일 수 없습니다.");
            }

            futures.add(self.uploadFileAsync(file, folder, type));
        }

        return futures.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .collect(Collectors.toList());
    }


    /**
     *  파일 업로드 시 비동기 처리 담당
     */
    @Async("s3UploadExecutor")
    public CompletableFuture<List<UploadResultResponse>> uploadFileAsync(MultipartFile file, String folder, FileType type) {
        try {
            return CompletableFuture.completedFuture(uploadFile(file, folder, type));
        } catch (Exception e) {
            log.error("파일 업로드 실패: {}", file.getOriginalFilename(), e);
            throw new ServiceException("500", "파일 업로드 실패: " + file.getOriginalFilename(), e);
        }
    }

    /**
     * 실제 파일 업로드하고, key와 url 생성 담당
     */
    public List<UploadResultResponse> uploadFile(MultipartFile file, String folder, FileType type) {
        try {
            List<UploadResultResponse> results = new ArrayList<>();

            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null || originalFilename.isBlank()) {
                throw new ServiceException("400", "파일 이름이 없습니다.");
            }

            String extension = originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase();
            FileCategory category = FileCategory.fromExtension(extension);
            String contentType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";

            // S3 Key 생성 (UUID 사용)
            String s3Key = folder + "/" + UUID.randomUUID() + "." + extension;
            // S3 업로드
            putS3Object(file.getBytes(), s3Key, contentType);
            // URL 생성
            String url = s3Client.utilities().getUrl(b -> b.bucket(bucketName).key(s3Key)).toString();

            results.add(new UploadResultResponse(url, type, s3Key, originalFilename));

            if (category == FileCategory.IMAGE && type == FileType.MAIN) {
                // 원본 비율 유지하면서 최대 111px로 제한
                byte[] thumbBytes = resizeImageSafe(file.getBytes(), 111, extension);

                // 썸네일 S3 Key 생성
                String thumbKey = folder + "/thumbnail-" + UUID.randomUUID() + "." + extension;

                //S3에 썸네일 이미지 업로드
                putS3Object(thumbBytes, thumbKey, contentType);
                String thumbnailUrl = s3Client.utilities().getUrl(b -> b.bucket(bucketName).key(thumbKey)).toString();

                results.add(new UploadResultResponse(thumbnailUrl, FileType.THUMBNAIL, thumbKey, "thumb_" + originalFilename));
            }

            return results;
        } catch (IOException e) {
            log.error("파일 처리 실패: {}", file.getOriginalFilename(), e);
            throw new ServiceException("500", "파일 처리 실패: " + file.getOriginalFilename());
        }
    }

    /**
     * 실제 파일 업로드 담당
     */
    private void putS3Object(byte[] data, String key, String contentType) {
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType(contentType)
                    .build();
            s3Client.putObject(request, RequestBody.fromBytes(data));
        } catch (Exception e) {
            log.error("S3 업로드 실패. key={}", key, e);
            throw new ServiceException("500", "S3 업로드 실패: " + key);
        }
    }

    /**
     * 썸네일 이미지 리사이징 담당 (원본 비율 유지, 최대 너비/높이 제한)
     */
    private byte[] resizeImageSafe(byte[] originalImageBytes, int maxSize, String extension) {
        try {
            BufferedImage originalImage = ImageIO.read(new ByteArrayInputStream(originalImageBytes));
            if (originalImage == null) throw new IOException("이미지 형식 오류");

            int originalWidth = originalImage.getWidth();
            int originalHeight = originalImage.getHeight();

            // 원본이 이미 작으면 리사이징하지 않음
            if (originalWidth <= maxSize && originalHeight <= maxSize) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ImageIO.write(originalImage, extension, bos);
                return bos.toByteArray();
            }

            // 원본 비율 계산
            double widthRatio = (double) maxSize / originalWidth;
            double heightRatio = (double) maxSize / originalHeight;

            // 더 작은 비율을 사용하여 원본 비율 유지하면서 최대 크기 안에 맞춤
            double ratio = Math.min(widthRatio, heightRatio);

            int newWidth = (int) (originalWidth * ratio);
            int newHeight = (int) (originalHeight * ratio);

            BufferedImage resized = new BufferedImage(newWidth, newHeight,
                    originalImage.getType() == 0 ? BufferedImage.TYPE_INT_ARGB : originalImage.getType());

            Graphics2D g = resized.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            g.drawImage(originalImage, 0, 0, newWidth, newHeight, null);
            g.dispose();

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(resized, extension, bos);
            return bos.toByteArray();

        } catch (IOException e) {
            throw new ServiceException("500", "썸네일 이미지 생성 실패");
        }
    }

    /**
     * 파일 종류 구분 메서드
     */
    public enum FileCategory {
        IMAGE, DOCUMENT, OTHER;

        public static FileCategory fromExtension(String extension) {
            String ext = extension.toLowerCase();
            if (List.of("jpg","jpeg","png","gif","bmp","webp","tiff","tif","svg","heic","heif","ico").contains(ext)) return IMAGE;
            if (List.of("pdf","doc","docx","xls","xlsx","ppt","pptx","hwp","txt","md","csv","odt","ods","odp","rtf").contains(ext)) return DOCUMENT;
            return OTHER;
        }
    }

    /**
     * 다운로드 메서드 (문서 전용)
     */
    public byte[] downloadFile(String key) {
        try {
            ResponseBytes<GetObjectResponse> s3Object = s3Client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build());
            return s3Object.asByteArray();
        } catch (NoSuchKeyException e) {
            throw new ServiceException("404", "S3에 파일이 존재하지 않습니다. key=" + key);
        } catch (Exception e) {
            log.error("파일 다운로드 실패: {}", key, e);
            throw new ServiceException("500", "파일 다운로드 실패: " + key);
        }
    }

    /**
     * S3에서 파일 삭제
     */
    public void deleteFile(String key) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build());
        } catch (Exception e) {
            log.error("S3 파일 삭제 실패. key: {}", key, e);
            throw new ServiceException("500", "S3 파일 삭제 실패: " + key);
        }
    }

    /**
     * 파일들을 S3에 업로드하고 URL 목록만 반환하는 메서드
     */
    public List<DescriptionImageUploadResponse> uploadFilesAndGetUrls(List<MultipartFile> files, String folder) {
        if (files == null || files.isEmpty()) {
            throw new ServiceException("400", "업로드할 파일이 없습니다.");
        }

        return files.stream()
                .map(file -> uploadFileAndGetUrl(file, folder))
                .collect(Collectors.toList());
    }

    private DescriptionImageUploadResponse uploadFileAndGetUrl(MultipartFile file, String folder) {
        try {
            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null || originalFilename.isBlank()) {
                throw new ServiceException("400", "파일 이름이 없습니다.");
            }

            String extension = originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase();
            String contentType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";

            String s3Key = folder + "/" + UUID.randomUUID() + "." + extension;

            putS3Object(file.getBytes(), s3Key, contentType);

            String url = s3Client.utilities().getUrl(b -> b.bucket(bucketName).key(s3Key)).toString();

            return new DescriptionImageUploadResponse(url);
        } catch (IOException e) {
            log.error("파일 처리 실패: {}", file.getOriginalFilename(), e);
            throw new ServiceException("500", "파일 처리 실패: " + file.getOriginalFilename());
        }
    }
}