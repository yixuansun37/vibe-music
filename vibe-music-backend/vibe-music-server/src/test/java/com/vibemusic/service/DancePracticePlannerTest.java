package com.vibemusic.service;

import com.vibemusic.exception.DancePlaylistValidationException;
import com.vibemusic.model.vo.DancePracticePlanVO;
import com.vibemusic.model.vo.DancePracticePlanVO.PracticeSong;
import com.vibemusic.model.vo.SongVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** 纯算法测试，无 Spring 容器、数据库、网络或 AI Key。 */
class DancePracticePlannerTest {

    private final DancePracticePlanner planner = new DancePracticePlanner();

    @Test
    void parsesDecimalSecondsAndRoundsOnlySubMillisecondFractionsUp() {
        assertEquals(OptionalLong.of(196_980), DancePracticePlanner.parseDurationMs("196.98"));
        assertEquals(OptionalLong.of(173_000), DancePracticePlanner.parseDurationMs("173.00"));
        assertEquals(OptionalLong.of(1_001), DancePracticePlanner.parseDurationMs("1.0001"));
        assertEquals(OptionalLong.of(1), DancePracticePlanner.parseDurationMs("0.0001"));
        assertEquals(OptionalLong.of(196_980), DancePracticePlanner.parseDurationMs(" 03:16.98 "));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "NaN", "Infinity", "-1", "0", "0.0", "1e3", "+10", ".5",
            "3:60", "1:2", "1:02:03", "9223372036854775.808", "999999999999999999999999999"})
    void rejectsInvalidOrOverflowingDurations(String duration) {
        assertTrue(DancePracticePlanner.parseDurationMs(duration).isEmpty());
    }

    @Test
    void keepsExactDecimalTotalsAndNeverExceedsTarget() {
        DancePracticePlanVO plan = plan(5, List.of(song(1, "99.99"), song(2, "99.99"),
                song(3, "99.99"), song(4, "0.03")));

        assertEquals(300_000, plan.targetDurationMs());
        assertEquals(300_000, plan.actualDurationMs());
        assertEquals(0, plan.shortfallMs());
        assertEquals(plan.actualDurationMs(), songs(plan).stream().mapToLong(PracticeSong::getDurationMs).sum());
        assertEquals(plan.actualDurationMs(), plan.sections().stream()
                .mapToLong(DancePracticePlanVO.Section::actualDurationMs).sum());
    }

    @Test
    void filtersInvalidCandidatesAndDeduplicatesRealIds() {
        List<SongVO> candidates = new ArrayList<>();
        candidates.add(song(7, "60"));
        candidates.add(song(7, "60"));
        candidates.add(song(8, "60"));
        candidates.add(song(9, "60"));
        candidates.add(song(10, "broken"));
        candidates.add(song(0, "60"));
        candidates.add(null);
        SongVO noId = song(11, "60");
        noId.setSongId(null);
        candidates.add(noId);

        DancePracticePlanVO plan = plan(5, candidates);

        assertEquals(3, plan.candidateCount());
        assertEquals(List.of(7L, 8L, 9L), ids(plan));
        assertTrue(plan.warnings().stream().anyMatch(w -> w.contains("重复")));
        assertTrue(plan.warnings().stream().anyMatch(w -> w.contains("时长缺失或无效")));
        assertTrue(plan.warnings().stream().anyMatch(w -> w.contains("无有效歌曲 ID")));
    }

    @Test
    void givesThreeNonemptySectionsWhenThreeWholeSongsFit() {
        DancePracticePlanVO plan = plan(10, List.of(song(9, "300"), song(3, "60"),
                song(2, "60"), song(1, "60")));

        assertEquals(List.of("warmup", "main", "freestyle"), plan.sections().stream()
                .map(DancePracticePlanVO.Section::key).toList());
        assertTrue(plan.sections().stream().allMatch(s -> !s.songs().isEmpty()));
        assertEquals(List.of(120_000L, 360_000L, 120_000L), plan.sections().stream()
                .map(DancePracticePlanVO.Section::targetDurationMs).toList());
        assertTrue(plan.actualDurationMs() <= plan.targetDurationMs());
    }

    @Test
    void isStableAcrossCandidateQueryOrder() {
        List<SongVO> candidates = List.of(song(4, "90"), song(1, "60"), song(3, "90"), song(2, "60"));
        DancePracticePlanVO first = plan(5, candidates);
        DancePracticePlanVO second = plan(5, List.of(candidates.get(3), candidates.get(2),
                candidates.get(1), candidates.get(0)));

        assertEquals(ids(first), ids(second));
        assertEquals(first.sections(), second.sections());
    }

    @Test
    void preferredIdsOnlyOrderExistingSongsAndCannotInventOrRepeatThem() {
        DancePracticePlanVO plan = planner.plan("hiphop", List.of("嘻哈说唱"), 5,
                List.of(song(1, "60"), song(2, "60"), song(3, "60")),
                List.of(999L, 3L, 3L, 2L), true, null);

        assertEquals(List.of(3L, 2L, 1L), ids(plan));
        assertEquals("AI_ASSISTED", plan.generationMode());
        assertEquals(3, ids(plan).stream().distinct().count());
        assertFalse(ids(plan).contains(999L));
    }

    @Test
    void respectsMaximumFiftySongsAndReportsTheLimit() {
        List<SongVO> candidates = IntStream.rangeClosed(1, 80)
                .mapToObj(i -> song(i, "1")).toList();

        DancePracticePlanVO plan = plan(5, candidates);

        assertEquals(50, songs(plan).size());
        assertEquals(50_000, plan.actualDurationMs());
        assertTrue(plan.warnings().stream().anyMatch(w -> w.contains("50 首")));
        assertEquals(50, ids(plan).stream().distinct().count());
    }

    @Test
    void oneSongGoesInMainAndTwoSongsLeaveFreestyleEmpty() {
        DancePracticePlanVO one = plan(5, List.of(song(1, "60")));
        DancePracticePlanVO two = plan(5, List.of(song(1, "60"), song(2, "60")));

        assertTrue(one.sections().get(0).songs().isEmpty());
        assertEquals(List.of(1L), one.sections().get(1).songs().stream().map(SongVO::getSongId).toList());
        assertTrue(one.sections().get(2).songs().isEmpty());
        assertEquals(1, two.sections().get(0).songs().size());
        assertEquals(1, two.sections().get(1).songs().size());
        assertTrue(two.sections().get(2).songs().isEmpty());
        assertTrue(two.warnings().stream().anyMatch(w -> w.contains("不足三首")));
    }

    @Test
    void reportsEmptyAndInsufficientCatalogWithoutInventingSongs() {
        DancePracticePlanVO empty = plan(5, List.of());
        DancePracticePlanVO insufficient = plan(5, List.of(song(1, "30"), song(2, "60")));

        assertTrue(songs(empty).isEmpty());
        assertEquals(300_000, empty.shortfallMs());
        assertTrue(empty.warnings().stream().anyMatch(w -> w.contains("没有有效歌曲")));
        assertEquals(90_000, insufficient.actualDurationMs());
        assertEquals(210_000, insufficient.shortfallMs());
        assertTrue(insufficient.warnings().stream().anyMatch(w -> w.contains("总时长不足")));
        assertEquals("DETERMINISTIC", insufficient.generationMode());
        assertTrue(insufficient.warnings().stream().anyMatch(w -> w.contains("非 AI")));
    }

    @Test
    void tooLongSongsAreNotClippedAndCatalogSumCannotOverflow() {
        DancePracticePlanVO plan = plan(5, List.of(song(1, "5728.39"),
                song(2, "9223372036854775.807"), song(3, "9223372036854775.807")));

        assertTrue(songs(plan).isEmpty());
        assertEquals(0, plan.actualDurationMs());
        assertEquals(300_000, plan.shortfallMs());
        assertFalse(plan.warnings().stream().anyMatch(w -> w.contains("总时长不足")));
        assertTrue(plan.warnings().stream().anyMatch(w -> w.contains("贪心算法不保证最优")));
    }

    @Test
    void copiesRealMetadataInsteadOfInventingOrChangingIt() {
        SongVO original = song(1, "60");
        original.setSongName("Database title");
        original.setArtistName("Database artist");
        original.setAudioUrl("/existing/audio/1");
        original.setCoverUrl(null);

        PracticeSong result = songs(plan(5, List.of(original))).get(0);

        assertNotSame(original, result);
        assertEquals("Database title", result.getSongName());
        assertEquals("Database artist", result.getArtistName());
        assertEquals("/existing/audio/1", result.getAudioUrl());
        assertNull(result.getCoverUrl());
        assertEquals("60", result.getDuration());
        assertEquals(60_000, result.getDurationMs());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 4, 91, Integer.MAX_VALUE})
    void rejectsOutOfRangeMinutes(int minutes) {
        assertThrows(DancePlaylistValidationException.class, () -> plan(minutes, List.of(song(1, "60"))));
    }

    @Test
    void acceptsBothMinuteBoundsAndRejectsMissingStyle() {
        assertEquals(300_000, plan(5, List.of()).targetDurationMs());
        assertEquals(5_400_000, plan(90, List.of()).targetDurationMs());
        assertThrows(DancePlaylistValidationException.class,
                () -> planner.plan(null, List.of(), 5, List.of(), null, false, null));
        assertThrows(DancePlaylistValidationException.class,
                () -> planner.plan(" ", List.of(), 5, List.of(), null, false, null));
    }

    @Test
    void nullListsAreSafeAndFallbackReasonRemainsVisible() {
        DancePracticePlanVO plan = planner.plan("hiphop", null, 5, null, null, false, "AI 未配置");
        assertTrue(plan.musicStyles().isEmpty());
        assertTrue(songs(plan).isEmpty());
        assertTrue(plan.warnings().contains("AI 未配置"));
    }

    private DancePracticePlanVO plan(int minutes, List<SongVO> candidates) {
        return planner.plan("hiphop", List.of("嘻哈说唱"), minutes, candidates, null, false, null);
    }

    private static SongVO song(long id, String duration) {
        SongVO song = new SongVO();
        song.setSongId(id);
        song.setSongName("Song " + id);
        song.setDuration(duration);
        return song;
    }

    private static List<PracticeSong> songs(DancePracticePlanVO plan) {
        return plan.sections().stream().flatMap(section -> section.songs().stream()).toList();
    }

    private static List<Long> ids(DancePracticePlanVO plan) {
        return songs(plan).stream().map(SongVO::getSongId).toList();
    }
}
