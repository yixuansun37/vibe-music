package com.vibemusic.model.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.IOException;
import java.util.List;

/** 保存真实曲库歌曲组成的公开练习歌单，不接受现有歌单 ID 或所有者信息。 */
@Data
public class DancePlaylistSaveDTO {

    @NotBlank(message = "标题不能为空")
    @Size(max = 255, message = "标题不能超过 255 个字符")
    private String title;

    @Size(max = 2000, message = "描述不能超过 2000 个字符")
    private String description;

    @NotEmpty(message = "至少选择一首歌曲")
    @Size(max = 50, message = "最多选择 50 首歌曲")
    @JsonDeserialize(contentUsing = SongIdDeserializer.class)
    private List<@NotNull(message = "歌曲 ID 不能为空")
            @Positive(message = "歌曲 ID 必须为正整数") Long> songIds;

    /** 禁止 Jackson 将 1.9 截断成歌曲 ID 1，仅对本接口生效。 */
    public static class SongIdDeserializer extends JsonDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                return context.reportInputMismatch(Long.class, "歌曲 ID 必须为整数");
            }
            return parser.getLongValue();
        }
    }
}
