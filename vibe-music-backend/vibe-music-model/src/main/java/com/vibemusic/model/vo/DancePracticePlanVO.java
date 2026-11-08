package com.vibemusic.model.vo;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** 分段仅用于预览；现有歌单关联表不持久化分段或播放顺序。 */
public record DancePracticePlanVO(
        String danceStyle,
        List<String> musicStyles,
        long targetDurationMs,
        long actualDurationMs,
        long shortfallMs,
        String generationMode,
        int candidateCount,
        List<String> warnings,
        List<Section> sections) {

    public record Section(String key, String title, long targetDurationMs,
                          long actualDurationMs, List<PracticeSong> songs) {
    }

    /** 元数据复制自数据库，额外的毫秒值只由服务器计算。 */
    @Getter
    @Setter
    public static class PracticeSong extends SongVO {
        private long durationMs;
    }
}
