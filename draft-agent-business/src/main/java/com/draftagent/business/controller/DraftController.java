package com.draftagent.business.controller;

import com.draftagent.business.dto.CreateDraftRequest;
import com.draftagent.business.dto.DraftCreateResponse;
import com.draftagent.business.dto.DraftStatusView;
import com.draftagent.business.entity.Draft;
import com.draftagent.business.service.DraftService;
import com.draftagent.common.core.domain.R;
import com.draftagent.common.security.CurrentUser;
import com.draftagent.common.security.CurrentUserProvider;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 草案接口（均要求认证；按当前用户/租户隔离）。
 */
@RestController
@RequestMapping("/drafts")
public class DraftController {

    private final DraftService draftService;

    private final CurrentUserProvider currentUserProvider;

    public DraftController(DraftService draftService, CurrentUserProvider currentUserProvider) {
        this.draftService = draftService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping
    public R<DraftCreateResponse> create(@Valid @RequestBody CreateDraftRequest request) {
        CurrentUser currentUser = currentUserProvider.getCurrentUser();
        Draft draft = draftService.createDraft(request.getTitle(), request.getContent(),
                currentUser.getUserId(), currentUser.getTenantId());
        DraftCreateResponse response = new DraftCreateResponse(
                draft.getId(), draft.getTitle(), draft.getStatus(), draft.getCreatedAt());
        return R.ok(response);
    }

    @GetMapping("/{draftId}/status")
    public R<DraftStatusView> queryStatus(@PathVariable Long draftId) {
        CurrentUser currentUser = currentUserProvider.getCurrentUser();
        return R.ok(draftService.queryDraftStatus(draftId, currentUser.getUserId(), currentUser.getTenantId()));
    }
}
