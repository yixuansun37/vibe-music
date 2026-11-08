package com.vibemusic.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibemusic.model.vo.SongVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** No Spring context, external services, or real AI requests are used here. */
class DancePracticeAiRankerTest {

    private ObjectProvider<ChatModel> provider;
    private ChatModel model;
    private MockEnvironment environment;
    private DancePracticeAiRanker ranker;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        provider = mock(ObjectProvider.class);
        model = mock(ChatModel.class);
        environment = new MockEnvironment();
        ranker = new DancePracticeAiRanker(provider, new ObjectMapper(), environment);
    }

    @Test
    void missingKeyUsesDeterministicOrderWithoutInitializingModel() {
        assertFallback(ranker.rank("hiphop", catalog()));
        verifyNoInteractions(provider, model);
    }

    @Test
    void disabledProviderIsNotInitializedEvenWithConfiguredKey() {
        environment.withProperty("spring.ai.dashscope.enabled", "false")
                .withProperty("spring.ai.dashscope.api-key", "unit-test-fixture");

        assertFallback(ranker.rank("hiphop", catalog()));

        verifyNoInteractions(provider, model);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "${missing.danceflow.test.key}"})
    void blankOrUnresolvedKeyDoesNotInitializeModel(String key) {
        environment.withProperty("spring.ai.dashscope.api-key", key);

        assertFallback(ranker.rank("hiphop", catalog()));

        verifyNoInteractions(provider, model);
    }

    @Test
    void missingModelFallsBack() {
        configureKey();

        assertFallback(ranker.rank("hiphop", catalog()));

        verify(provider).getIfAvailable();
        verifyNoInteractions(model);
    }

    @Test
    void validMockModelResponseOrdersOnlyRealCandidateIds() {
        respondWith("{\"songIds\":[3,1,2]}");

        DancePracticeAiRanker.Ranking result = ranker.rank("hiphop", catalog());

        assertEquals(List.of(3L, 1L, 2L), result.songIds());
        assertTrue(result.aiUsed());
        assertTrue(result.message().contains("时长计算"));
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        String payload = prompt.getValue().getUserMessage().getText();
        assertTrue(payload.contains("\"songId\":1"));
        assertTrue(payload.contains("\"danceStyle\":\"hiphop\""));
        assertFalse(payload.contains("audioUrl"));
        assertFalse(payload.contains("duration"));
        assertFalse(payload.contains("unit-test-fixture"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"songIds\":[1,2,999]}",
            "{\"songIds\":[1,1,3]}",
            "{\"songIds\":[1,2,3.0]}",
            "{\"songIds\":[1,2,3.5]}",
            "{\"songIds\":[1,2,\"3\"]}",
            "{\"songIds\":[1,2,9223372036854775808]}",
            "{\"songIds\":[1,2,null]}",
            "{\"songIds\":[1,2]}",
            "{\"songIds\":[]}",
            "{\"songIds\":[1,2,3],\"bpm\":120}",
            "{\"songIds\":[1,2,3]}{\"songIds\":[3,2,1]}",
            "{\"songIds\":[1,2,3],\"songIds\":[3,2,1]}",
            "[]",
            "not JSON",
            ""
    })
    void malformedOrInventedModelOutputFallsBackToEntireCatalog(String content) {
        respondWith(content);

        assertFallback(ranker.rank("hiphop", catalog()));
        verify(model).call(any(Prompt.class));
    }

    @Test
    void remoteErrorFallsBackWithoutExposingExceptionMessage() {
        configureModel();
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("private-diagnostic-fixture"));

        DancePracticeAiRanker.Ranking result = ranker.rank("house", catalog());

        assertFallback(result);
        assertFalse(result.message().contains("private-diagnostic-fixture"));
    }

    @Test
    void providerInitializationErrorFallsBackWithoutExposingExceptionMessage() {
        configureKey();
        when(provider.getIfAvailable()).thenThrow(new IllegalStateException("private-provider-fixture"));

        DancePracticeAiRanker.Ranking result = ranker.rank("house", catalog());

        assertFallback(result);
        assertFalse(result.message().contains("private-provider-fixture"));
        verifyNoInteractions(model);
    }

    @Test
    void emptyCandidatesDoNotInitializeModel() {
        configureKey();

        DancePracticeAiRanker.Ranking result = ranker.rank("jazz", List.of());

        assertEquals(List.of(), result.songIds());
        assertFalse(result.aiUsed());
        verifyNoInteractions(provider, model);
    }

    private void configureKey() {
        // A non-secret unit-test fixture; all model calls are mocked.
        environment.withProperty("spring.ai.dashscope.api-key", "unit-test-fixture");
    }

    private void configureModel() {
        configureKey();
        when(provider.getIfAvailable()).thenReturn(model);
    }

    private void respondWith(String content) {
        configureModel();
        when(model.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage(content)))));
    }

    private List<SongVO> catalog() {
        return List.of(song(1L), song(2L), song(3L));
    }

    private SongVO song(long id) {
        SongVO song = new SongVO();
        song.setSongId(id);
        song.setSongName("Catalog song " + id);
        song.setArtistName("Catalog artist");
        song.setDuration("180.50");
        song.setAudioUrl("/fixture-audio/" + id);
        return song;
    }

    private void assertFallback(DancePracticeAiRanker.Ranking result) {
        assertEquals(List.of(1L, 2L, 3L), result.songIds());
        assertFalse(result.aiUsed());
        assertTrue(result.message().contains("非 AI"));
    }
}
