package com.tracker.rag.e2e.contract;

import java.util.UUID;

public interface CaptureService {
    ConfirmationCard createPreviewCard(String naturalLanguageInput, DomainScope userSelectedDomain);

    Object commitConfirmation(UUID confirmationToken);

    void cancelPreviewCard(UUID confirmationToken);
}
