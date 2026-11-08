package com.vibemusic.controller;

import com.vibemusic.exception.DancePlaylistValidationException;
import com.vibemusic.model.dto.DancePracticeGenerateDTO;
import com.vibemusic.model.vo.DancePracticePlanVO;
import com.vibemusic.result.Result;
import com.vibemusic.service.DancePracticeService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 继承现有 /playlist/ 用户鉴权，不增加公开白名单或管理员权限。 */
@RestController
@RequestMapping("/playlist/dance")
public class DancePracticeController {

    private final DancePracticeService practiceService;

    public DancePracticeController(DancePracticeService practiceService) {
        this.practiceService = practiceService;
    }

    @PostMapping("/generate")
    public Result<DancePracticePlanVO> generate(@RequestBody @Valid DancePracticeGenerateDTO request) {
        return Result.success(practiceService.generate(request));
    }

    @ExceptionHandler(DancePlaylistValidationException.class)
    public ResponseEntity<Result<Void>> invalidSelection(DancePlaylistValidationException error) {
        return ResponseEntity.badRequest().body(new Result<>(1, error.getMessage(), null));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> invalidJson(HttpMessageNotReadableException error) {
        return ResponseEntity.badRequest().body(new Result<>(1, "请求 JSON 格式或字段类型错误", null));
    }
}
