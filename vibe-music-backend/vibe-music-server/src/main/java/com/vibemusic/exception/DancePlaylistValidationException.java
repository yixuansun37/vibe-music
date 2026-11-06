package com.vibemusic.exception;

/** 练习歌单的输入或歌曲选择无效，映射为 HTTP 400。 */
public class DancePlaylistValidationException extends RuntimeException {

    public DancePlaylistValidationException(String message) {
        super(message);
    }
}
