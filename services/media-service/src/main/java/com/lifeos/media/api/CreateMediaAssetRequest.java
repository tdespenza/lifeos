package com.lifeos.media.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Metadata first: source bytes are accepted only by the separately authorized bounded upload route. */
public record CreateMediaAssetRequest(@NotBlank @Size(max = 140) String title) {
}
