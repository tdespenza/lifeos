package com.lifeos.media.api;

import com.lifeos.media.domain.MediaSessionArtifact;
import java.util.List;
import java.util.UUID;

/** Public-safe transcript, summary, and action-item projection. */
public record MediaSessionArtifactResponse(
        UUID id,
        UUID sessionId,
        String transcriptionMode,
        String processingState,
        String transcript,
        String summary,
        List<String> actionItems,
        long version) {

    public static MediaSessionArtifactResponse from(MediaSessionArtifact artifact, List<String> actionItems) {
        return new MediaSessionArtifactResponse(
                artifact.getId(),
                artifact.getSessionId(),
                artifact.getTranscriptionMode(),
                artifact.getProcessingState(),
                artifact.getTranscript(),
                artifact.getSummary(),
                List.copyOf(actionItems),
                artifact.getVersion());
    }
}
