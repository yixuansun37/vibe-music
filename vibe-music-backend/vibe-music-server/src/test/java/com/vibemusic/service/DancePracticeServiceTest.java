package com.vibemusic.service;

import com.vibemusic.agent.DancePracticeAiRanker;
import com.vibemusic.agent.DancePracticeAiRanker.Ranking;
import com.vibemusic.agent.MusicAgentTools;
import com.vibemusic.exception.DancePlaylistValidationException;
import com.vibemusic.model.dto.DancePracticeGenerateDTO;
import com.vibemusic.model.vo.DancePracticePlanVO;
import com.vibemusic.model.vo.SongVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Pure service orchestration; catalog and optional AI are mocked, never remote. */
class DancePracticeServiceTest {

    private MusicAgentTools musicTools;
    private DancePracticeAiRanker aiRanker;

    @BeforeEach
    void setUp() {
        musicTools = mock(MusicAgentTools.class);
        aiRanker = mock(DancePracticeAiRanker.class);
    }

    @ParameterizedTest
    @CsvSource({"hiphop,嘻哈说唱", "house,电子", "jazz,节奏布鲁斯"})
    void queriesOnlyConfiguredMusicStyleAndPassesRankingToPlanner(String danceStyle, String musicStyle) {
        DancePracticePlanner planner = mock(DancePracticePlanner.class);
        DancePracticeService service = new DancePracticeService(musicTools, aiRanker, planner);
        List<String> styles = List.of(musicStyle);
        List<SongVO> candidates = List.of(song(8L, "180"), song(31L, "200"));
        Ranking ranking = new Ranking(List.of(31L, 8L), true, "AI 仅排序");
        DancePracticePlanVO expected = new DancePracticePlanVO(
                danceStyle, styles, 1_200_000L, 380_000L, 820_000L,
                "AI_ASSISTED", 2, List.of("测试预览"), List.of());
        when(musicTools.practiceCandidates(styles)).thenReturn(candidates);
        when(aiRanker.rank(danceStyle, candidates)).thenReturn(ranking);
        when(planner.plan(danceStyle, styles, 20, candidates, ranking.songIds(), true, ranking.message()))
                .thenReturn(expected);

        assertSame(expected, service.generate(request(danceStyle, 20)));

        verify(musicTools).practiceCandidates(styles);
        verify(aiRanker).rank(danceStyle, candidates);
        verify(planner).plan(danceStyle, styles, 20, candidates, ranking.songIds(), true, ranking.message());
        verifyNoMoreInteractions(musicTools, aiRanker, planner);
    }

    @Test
    void emptyStyleCatalogProducesThreeEmptySectionsAndExplicitWarningsWithoutOtherStyleQueries() {
        DancePracticeService service = new DancePracticeService(musicTools, aiRanker, new DancePracticePlanner());
        when(musicTools.practiceCandidates(List.of("电子"))).thenReturn(List.of());
        when(aiRanker.rank("house", List.of())).thenReturn(new Ranking(List.of(), false, "未启用 AI"));

        DancePracticePlanVO plan = service.generate(request("house", 20));

        assertEquals("house", plan.danceStyle());
        assertEquals(List.of("电子"), plan.musicStyles());
        assertEquals("DETERMINISTIC", plan.generationMode());
        assertEquals(0, plan.candidateCount());
        assertEquals(1_200_000L, plan.targetDurationMs());
        assertEquals(0L, plan.actualDurationMs());
        assertEquals(1_200_000L, plan.shortfallMs());
        assertEquals(List.of("warmup", "main", "freestyle"),
                plan.sections().stream().map(DancePracticePlanVO.Section::key).toList());
        assertTrue(plan.sections().stream().allMatch(section -> section.songs().isEmpty()));
        assertTrue(plan.sections().stream().allMatch(section -> section.actualDurationMs() == 0L));
        assertTrue(plan.warnings().stream().anyMatch(warning -> warning.contains("没有有效歌曲")));
        assertTrue(plan.warnings().stream().anyMatch(warning -> warning.contains("未改用其他风格")));
        verify(musicTools).practiceCandidates(List.of("电子"));
        verifyNoMoreInteractions(musicTools);
    }

    @Test
    void deterministicFallbackUsesOnlyRealCandidatesAndKeepsItsExplanation() {
        DancePracticeService service = new DancePracticeService(musicTools, aiRanker, new DancePracticePlanner());
        List<SongVO> candidates = List.of(song(8L, "60"), song(31L, "60"), song(12L, "60"));
        String fallbackReason = "AI 不可用，使用确定性规则";
        when(musicTools.practiceCandidates(List.of("节奏布鲁斯"))).thenReturn(candidates);
        when(aiRanker.rank("jazz", candidates))
                .thenReturn(new Ranking(List.of(8L, 31L, 12L), false, fallbackReason));

        DancePracticePlanVO plan = service.generate(request("jazz", 5));

        assertEquals("DETERMINISTIC", plan.generationMode());
        assertEquals(List.of(8L, 31L, 12L), plannedIds(plan));
        assertEquals(180_000L, plan.actualDurationMs());
        assertEquals(120_000L, plan.shortfallMs());
        assertTrue(plan.warnings().contains(fallbackReason));
        assertFalse(plan.sections().stream().anyMatch(section -> section.songs().isEmpty()));
        assertEquals(180_000L, plan.sections().stream().mapToLong(DancePracticePlanVO.Section::actualDurationMs).sum());
        verify(musicTools).practiceCandidates(List.of("节奏布鲁斯"));
        verifyNoMoreInteractions(musicTools);
    }

    @Test
    void aiAssistedPreviewPreservesDatabaseMetadataAndCalculatesTimeWithRealPlanner() {
        DancePracticeService service = new DancePracticeService(musicTools, aiRanker, new DancePracticePlanner());
        List<SongVO> candidates = List.of(song(8L, "60"), song(31L, "60"), song(12L, "60"));
        when(musicTools.practiceCandidates(List.of("嘻哈说唱"))).thenReturn(candidates);
        when(aiRanker.rank("hiphop", candidates))
                .thenReturn(new Ranking(List.of(31L, 8L, 12L), true, "AI 辅助排序"));

        DancePracticePlanVO plan = service.generate(request("hiphop", 5));

        assertEquals("AI_ASSISTED", plan.generationMode());
        assertEquals(List.of(31L, 8L, 12L), plannedIds(plan));
        assertEquals(300_000L, plan.targetDurationMs());
        assertEquals(180_000L, plan.actualDurationMs());
        assertEquals(120_000L, plan.shortfallMs());
        var first = plan.sections().get(0).songs().get(0);
        assertEquals("song-31", first.getSongName());
        assertEquals("https://media.invalid/31.mp3", first.getAudioUrl());
        assertEquals(60_000L, first.getDurationMs());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ballet", "hip-hop", "HIPHOP", ""})
    void unsupportedDanceStyleIsRejectedBeforeCatalogOrAiAccess(String style) {
        DancePracticePlanner planner = mock(DancePracticePlanner.class);
        DancePracticeService service = new DancePracticeService(musicTools, aiRanker, planner);

        assertThrows(DancePlaylistValidationException.class, () -> service.generate(request(style, 20)));

        verifyNoInteractions(musicTools, aiRanker, planner);
    }

    private static DancePracticeGenerateDTO request(String style, int minutes) {
        DancePracticeGenerateDTO request = new DancePracticeGenerateDTO();
        request.setDanceStyle(style);
        request.setTargetMinutes(minutes);
        return request;
    }

    private static SongVO song(long id, String duration) {
        SongVO song = new SongVO();
        song.setSongId(id);
        song.setSongName("song-" + id);
        song.setDuration(duration);
        song.setAudioUrl("https://media.invalid/" + id + ".mp3");
        return song;
    }

    private static List<Long> plannedIds(DancePracticePlanVO plan) {
        return plan.sections().stream().flatMap(section -> section.songs().stream())
                .map(SongVO::getSongId).toList();
    }
}
