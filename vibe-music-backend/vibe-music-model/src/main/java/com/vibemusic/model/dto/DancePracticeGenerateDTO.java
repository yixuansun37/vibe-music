package com.vibemusic.model.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.io.IOException;

/** 练舞预览请求；音乐风格映射不是 BPM 或舞种标注。 */
@Data
public class DancePracticeGenerateDTO {

    @NotNull(message = "请选择舞种")
    @Pattern(regexp = "hiphop|house|jazz", message = "支持的舞种为 hiphop、house、jazz")
    private String danceStyle;

    @NotNull(message = "请输入目标分钟数")
    @Min(value = 5, message = "目标时长至少为 5 分钟")
    @Max(value = 90, message = "目标时长最多为 90 分钟")
    @JsonDeserialize(using = MinutesDeserializer.class)
    private Integer targetMinutes;

    public static class MinutesDeserializer extends JsonDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                return context.reportInputMismatch(Integer.class, "目标分钟数必须为整数");
            }
            return parser.getIntValue();
        }
    }
}
