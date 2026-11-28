package com.draftagent.business.controller;

import com.draftagent.business.dto.DocumentUploadResponse;
import com.draftagent.business.service.DocumentService;
import com.draftagent.common.core.domain.R;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文档接口（Batch 3.3：仅上传）。所有接口均要求认证。
 */
@RestController
@RequestMapping("/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<DocumentUploadResponse> upload(@RequestParam("file") MultipartFile file) {
        return R.ok(this.documentService.upload(file));
    }
}
