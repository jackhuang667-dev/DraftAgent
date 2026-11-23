package com.draftagent.business.controller;

import com.draftagent.business.dto.CreateDraftRequest;
import com.draftagent.business.dto.DraftCreateResponse;
import com.draftagent.business.dto.DraftStatusView;
import com.draftagent.business.entity.Draft;
import com.draftagent.business.service.DraftService;
import com.draftagent.common.core.domain.R;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 草案接口。
 */
@RestController
@RequestMapping("/drafts")
public class DraftController {

    private final DraftService draftService;

    public DraftController(DraftService draftService) {
        this.draftService = draftService;
    }

    @PostMapping
    public R<DraftCreateResponse> create(@Valid @RequestBody CreateDraftRequest request) {
        Draft draft = draftService.createDraft(request.getTitle(), request.getContent());
        DraftCreateResponse response = new DraftCreateResponse(
                draft.getId(), draft.getTitle(), draft.getStatus(), draft.getCreatedAt());
        return R.ok(response);
    }

    @GetMapping("/{draftId}/status")
    public R<DraftStatusView> queryStatus(@PathVariable Long draftId) {
        return R.ok(draftService.queryDraftStatus(draftId));
    }
}
