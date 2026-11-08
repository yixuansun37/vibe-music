package com.vibemusic.service;

import com.vibemusic.agent.DancePracticeAiRanker;
import com.vibemusic.agent.MusicAgentTools;
import com.vibemusic.exception.DancePlaylistValidationException;
import com.vibemusic.model.dto.DancePracticeGenerateDTO;
import com.vibemusic.model.vo.DancePracticePlanVO;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** 生成不写库；用户确认后仍通过已有、受登录保护的保存接口写入。 */
@Service
public class DancePracticeService {

    private static final Map<String, List<String>> STYLE_MAPPING = Map.of(
            "hiphop", List.of("嘻哈说唱"),
            "house", List.of("电子"),
            "jazz", List.of("节奏布鲁斯"));

    private final MusicAgentTools musicTools;
    private final DancePracticeAiRanker aiRanker;
    private final DancePracticePlanner planner;

    public DancePracticeService(MusicAgentTools musicTools, DancePracticeAiRanker aiRanker,
                                DancePracticePlanner planner) {
        this.musicTools = musicTools;
        this.aiRanker = aiRanker;
        this.planner = planner;
    }

    public DancePracticePlanVO generate(DancePracticeGenerateDTO request) {
        List<String> styles = STYLE_MAPPING.get(request.getDanceStyle());
        if (styles == null) {
            throw new DancePlaylistValidationException("该舞种没有已配置的音乐风格映射");
        }
        var candidates = musicTools.practiceCandidates(styles);
        var ranking = aiRanker.rank(request.getDanceStyle(), candidates);
        return planner.plan(request.getDanceStyle(), styles, request.getTargetMinutes(), candidates,
                ranking.songIds(), ranking.aiUsed(), ranking.message());
    }
}
