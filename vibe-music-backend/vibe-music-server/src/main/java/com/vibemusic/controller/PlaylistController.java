package com.vibemusic.controller;


import com.vibemusic.exception.DancePlaylistValidationException;
import com.vibemusic.model.dto.DancePlaylistSaveDTO;
import com.vibemusic.model.dto.PlaylistDTO;
import com.vibemusic.model.vo.PlaylistDetailVO;
import com.vibemusic.model.vo.PlaylistVO;
import com.vibemusic.result.PageResult;
import com.vibemusic.result.Result;
import com.vibemusic.service.IPlaylistService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author sunpingli
 * @since 2025-01-09
 */
@RestController
@RequestMapping("/playlist")
public class PlaylistController {

    @Autowired
    private IPlaylistService playlistService;

    /**
     * 保存新的公开练习歌单。现有登录拦截器校验用户 token，管理员接口权限不变。
     * 当前关联表没有排序列，因此请求顺序不能持久化为播放顺序。
     */
    @PostMapping("/dance/save")
    public Result<Long> saveDancePlaylist(@RequestBody @Valid DancePlaylistSaveDTO dto) {
        return playlistService.saveDancePlaylist(dto);
    }

    @ExceptionHandler(DancePlaylistValidationException.class)
    public ResponseEntity<Result<Void>> handleDancePlaylistValidation(DancePlaylistValidationException ex) {
        return ResponseEntity.badRequest().body(new Result<>(1, ex.getMessage(), null));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleUnreadableRequest(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(new Result<>(1, "请求 JSON 格式或字段类型错误", null));
    }

    /**
     * 获取所有歌单
     *
     * @param playlistDTO playlistDTO
     * @return 歌单列表
     */
    @PostMapping("/getAllPlaylists")
    public Result<PageResult<PlaylistVO>> getAllPlaylists(@RequestBody @Valid PlaylistDTO playlistDTO) {
        return playlistService.getAllPlaylists(playlistDTO);
    }

    /**
     * 获取推荐歌单
     *
     * @param request request
     * @return 推荐歌单列表
     */
    @GetMapping("/getRecommendedPlaylists")
    public Result<List<PlaylistVO>> getRandomPlaylists(HttpServletRequest request) {
        return playlistService.getRecommendedPlaylists(request);
    }

    /**
     * 获取歌单详情
     *
     * @param playlistId 歌单id
     * @return 歌单详情
     */
    @GetMapping("/getPlaylistDetail/{id}")
    public Result<PlaylistDetailVO> getPlaylistDetail(@PathVariable("id") Long playlistId, HttpServletRequest request) {
        return playlistService.getPlaylistDetail(playlistId, request);
    }


}
