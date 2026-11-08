package com.vibemusic.service;

import com.vibemusic.exception.DancePlaylistValidationException;
import com.vibemusic.model.vo.DancePracticePlanVO;
import com.vibemusic.model.vo.DancePracticePlanVO.PracticeSong;
import com.vibemusic.model.vo.DancePracticePlanVO.Section;
import com.vibemusic.model.vo.SongVO;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;

/** 只安排真实候选歌曲；LLM 无权决定时长、生成歌曲或突破保存上限。 */
@Component
public class DancePracticePlanner {

    private static final int MAX_SONGS = 50;

    public DancePracticePlanVO plan(String danceStyle, List<String> musicStyles, int targetMinutes,
                                    List<SongVO> candidates, List<Long> preferredIds,
                                    boolean aiUsed, String aiMessage) {
        if (danceStyle == null || danceStyle.isBlank()) {
            throw new DancePlaylistValidationException("请选择舞种");
        }
        if (targetMinutes < 5 || targetMinutes > 90) {
            throw new DancePlaylistValidationException("练习时长必须为 5 至 90 分钟");
        }
        long targetMs = targetMinutes * 60_000L;
        List<String> warnings = new ArrayList<>();
        warnings.add("舞种仅按曲库音乐风格进行启发式匹配；未验证 BPM、速度或动作适配。");
        warnings.add("分段仅用于本次预览；现有数据库不保证保存后的歌曲顺序，也不保存分段。");

        Map<Long, Candidate> unique = new TreeMap<>();
        int invalidIds = 0;
        int invalidDurations = 0;
        int duplicates = 0;
        for (SongVO song : candidates == null ? List.<SongVO>of() : candidates) {
            if (song == null || song.getSongId() == null || song.getSongId() <= 0) {
                invalidIds++;
                continue;
            }
            OptionalLong duration = parseDurationMs(song.getDuration());
            if (duration.isEmpty()) {
                invalidDurations++;
                continue;
            }
            if (unique.putIfAbsent(song.getSongId(), new Candidate(song, duration.getAsLong())) != null) {
                duplicates++;
            }
        }
        if (invalidIds > 0) {
            warnings.add("已跳过 " + invalidIds + " 条无有效歌曲 ID 的候选。");
        }
        if (invalidDurations > 0) {
            warnings.add("已跳过 " + invalidDurations + " 首时长缺失或无效的歌曲。");
        }
        if (duplicates > 0) {
            warnings.add("已移除 " + duplicates + " 条重复歌曲候选。");
        }
        if (unique.isEmpty()) {
            warnings.add("当前音乐风格下没有有效歌曲；未改用其他风格或生成虚构歌曲。");
        }

        Map<Long, Integer> ranks = new HashMap<>();
        if (preferredIds != null) {
            for (Long id : preferredIds) {
                if (id != null && unique.containsKey(id)) {
                    ranks.putIfAbsent(id, ranks.size());
                }
            }
        }
        Comparator<Candidate> priority = Comparator
                .comparingInt((Candidate song) -> ranks.getOrDefault(song.song().getSongId(), Integer.MAX_VALUE))
                .thenComparing(song -> song.song().getSongId());
        List<Candidate> pool = new ArrayList<>(unique.values());
        List<Candidate> shortestFirst = pool.stream()
                .sorted(Comparator.comparingLong(Candidate::durationMs).thenComparing(priority)).toList();
        List<Candidate> selected = new ArrayList<>();
        Set<Long> selectedIds = new HashSet<>();
        long remainingMs = targetMs;

        // 先保留至多三首最短歌：只要三首整歌能放下，就能提供三个非空分段。
        for (Candidate candidate : shortestFirst.subList(0, Math.min(3, shortestFirst.size()))) {
            if (candidate.durationMs() > remainingMs) {
                break;
            }
            selected.add(candidate);
            selectedIds.add(candidate.song().getSongId());
            remainingMs -= candidate.durationMs();
        }
        List<Candidate> longestFirst = pool.stream()
                .sorted(Comparator.comparingLong(Candidate::durationMs).reversed().thenComparing(priority)).toList();
        for (Candidate candidate : longestFirst) {
            if (selected.size() == MAX_SONGS) {
                break;
            }
            if (!selectedIds.contains(candidate.song().getSongId()) && candidate.durationMs() <= remainingMs) {
                selected.add(candidate);
                selectedIds.add(candidate.song().getSongId());
                remainingMs -= candidate.durationMs();
            }
        }
        selected.sort(priority);
        long actualMs = targetMs - remainingMs;

        // 饱和相加避免不可信、超长时长导致溢出；只用此值判断候选总量不足。
        long catalogMs = 0;
        for (Candidate candidate : pool) {
            catalogMs = candidate.durationMs() > Long.MAX_VALUE - catalogMs
                    ? Long.MAX_VALUE : catalogMs + candidate.durationMs();
        }
        if (!pool.isEmpty() && catalogMs < targetMs) {
            warnings.add("当前风格有效候选的总时长不足目标，仅有 " + seconds(catalogMs) + " 秒。");
        }
        if (selected.size() < 3) {
            warnings.add("当前选曲不足三首，无法提供三个非空练习分段。");
        }
        if (remainingMs > 0) {
            warnings.add("本次方案比目标少 " + seconds(remainingMs)
                    + " 秒；按整首歌曲选取，贪心算法不保证最优凑时长。");
        }
        if (selected.size() == MAX_SONGS && remainingMs > 0) {
            warnings.add("已达到最多 50 首的保存上限。");
        }
        warnings.add(aiUsed ? "AI 仅辅助候选排序；时长与分段由确定性代码计算。"
                : "当前使用确定性规则生成（非 AI）。");
        if (aiMessage != null && !aiMessage.isBlank()) {
            warnings.add(aiMessage);
        }
        List<String> styles = musicStyles == null ? List.of()
                : musicStyles.stream().filter(Objects::nonNull).toList();
        return new DancePracticePlanVO(danceStyle, styles, targetMs, actualMs, remainingMs,
                aiUsed ? "AI_ASSISTED" : "DETERMINISTIC", pool.size(), List.copyOf(warnings),
                sections(selected, targetMs, actualMs));
    }

    /** 曲库使用秒数字符串；也兼容严格的 mm:ss，小于 1 毫秒的部分向上取整。 */
    public static OptionalLong parseDurationMs(String duration) {
        if (duration == null || duration.isBlank()) {
            return OptionalLong.empty();
        }
        String value = duration.trim();
        try {
            BigDecimal seconds;
            if (value.matches("\\d+(?:\\.\\d+)?")) {
                seconds = new BigDecimal(value);
            } else if (value.matches("\\d+:[0-5]\\d(?:\\.\\d+)?")) {
                String[] parts = value.split(":", 2);
                seconds = new BigDecimal(parts[0]).multiply(BigDecimal.valueOf(60))
                        .add(new BigDecimal(parts[1]));
            } else {
                return OptionalLong.empty();
            }
            long milliseconds = seconds.movePointRight(3).setScale(0, RoundingMode.CEILING).longValueExact();
            return milliseconds > 0 ? OptionalLong.of(milliseconds) : OptionalLong.empty();
        } catch (ArithmeticException | NumberFormatException exception) {
            return OptionalLong.empty();
        }
    }

    private List<Section> sections(List<Candidate> selected, long targetMs, long actualMs) {
        int warmEnd = 0;
        int mainEnd = selected.size();
        if (selected.size() == 2) {
            warmEnd = 1;
        } else if (selected.size() >= 3) {
            long cumulativeMs = 0;
            for (int i = 0; i < selected.size() - 2; i++) {
                cumulativeMs += selected.get(i).durationMs();
                warmEnd = i + 1;
                if (cumulativeMs * 5 >= actualMs) {
                    break;
                }
            }
            for (int i = warmEnd; i < selected.size() - 1; i++) {
                cumulativeMs += selected.get(i).durationMs();
                mainEnd = i + 1;
                if (cumulativeMs * 5 >= actualMs * 4) {
                    break;
                }
            }
        }
        return List.of(section("warmup", "热身", targetMs / 5, selected.subList(0, warmEnd)),
                section("main", "主练", targetMs * 3 / 5, selected.subList(warmEnd, mainEnd)),
                section("freestyle", "自由练习", targetMs / 5, selected.subList(mainEnd, selected.size())));
    }

    private Section section(String key, String title, long targetMs, List<Candidate> selected) {
        List<PracticeSong> songs = selected.stream().map(candidate -> {
            PracticeSong song = new PracticeSong();
            BeanUtils.copyProperties(candidate.song(), song);
            song.setDurationMs(candidate.durationMs());
            return song;
        }).toList();
        long actualMs = selected.stream().mapToLong(Candidate::durationMs).sum();
        return new Section(key, title, targetMs, actualMs, songs);
    }

    private static String seconds(long milliseconds) {
        return BigDecimal.valueOf(milliseconds, 3).stripTrailingZeros().toPlainString();
    }

    private record Candidate(SongVO song, long durationMs) {
    }
}
