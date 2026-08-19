package com.lifeos.profile.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateJournalEntryRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 65_536) String content) {
}
