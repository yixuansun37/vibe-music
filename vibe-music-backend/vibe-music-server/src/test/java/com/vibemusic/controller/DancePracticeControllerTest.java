package com.vibemusic.controller;

import com.vibemusic.config.RolePathPermissionsConfig;
import com.vibemusic.config.RolePermissionManager;
import com.vibemusic.constant.JwtClaimsConstant;
import com.vibemusic.exception.DancePlaylistValidationException;
import com.vibemusic.handler.GlobalExceptionHandler;
import com.vibemusic.interceptor.LoginInterceptor;
import com.vibemusic.model.dto.DancePracticeGenerateDTO;
import com.vibemusic.model.vo.DancePracticePlanVO;
import com.vibemusic.service.DancePracticeService;
import com.vibemusic.util.JwtUtil;
import com.vibemusic.util.ThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real MVC validation and existing authentication, with no Boot or live Redis. */
class DancePracticeControllerTest {

    private static final String GENERATE_PATH = "/playlist/dance/generate";
    private static final String VALID_REQUEST = "{\"danceStyle\":\"hiphop\",\"targetMinutes\":20}";

    private DancePracticeService practiceService;
    private ValueOperations<String, String> redisValues;
    private LocalValidatorFactoryBean validator;
    private MockMvc mockMvc;
    private String userToken;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        practiceService = mock(DancePracticeService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        redisValues = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(redisValues);
        when(redisValues.get(anyString())).thenReturn("active-test-session");

        YamlMapFactoryBean yaml = new YamlMapFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Map<String, Object> document = yaml.getObject();
        assertNotNull(document);
        Map<?, ?> configuredRoles = (Map<?, ?>) document.get("role-path-permissions");
        RolePathPermissionsConfig permissions = new RolePathPermissionsConfig();
        permissions.setPermissions((Map<String, List<String>>) configuredRoles.get("permissions"));

        LoginInterceptor interceptor = new LoginInterceptor();
        ReflectionTestUtils.setField(interceptor, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(interceptor, "rolePermissionManager", new RolePermissionManager(permissions));
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new DancePracticeController(practiceService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .addInterceptors(interceptor)
                .build();

        // Generated for this run only; neither the token nor signing key is printed.
        userToken = JwtUtil.generateToken(Map.of(
                JwtClaimsConstant.USER_ID, 17,
                JwtClaimsConstant.ROLE, "ROLE_USER"));
    }

    @AfterEach
    void tearDown() {
        ThreadLocalUtil.remove();
        validator.close();
    }

    @Test
    void authenticatedUserReceivesPracticePreview() throws Exception {
        DancePracticePlanVO preview = new DancePracticePlanVO(
                "hiphop", List.of("嘻哈说唱"), 1_200_000L, 900_000L, 300_000L,
                "DETERMINISTIC", 5, List.of("候选歌曲不足，未补入其他风格"),
                List.of(new DancePracticePlanVO.Section("warmup", "热身", 240_000L, 180_000L, List.of())));
        when(practiceService.generate(any(DancePracticeGenerateDTO.class))).thenReturn(preview);

        mockMvc.perform(post(GENERATE_PATH)
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.danceStyle").value("hiphop"))
                .andExpect(jsonPath("$.data.musicStyles[0]").value("嘻哈说唱"))
                .andExpect(jsonPath("$.data.targetDurationMs").value(1_200_000))
                .andExpect(jsonPath("$.data.actualDurationMs").value(900_000))
                .andExpect(jsonPath("$.data.shortfallMs").value(300_000))
                .andExpect(jsonPath("$.data.warnings[0]").value("候选歌曲不足，未补入其他风格"));

        ArgumentCaptor<DancePracticeGenerateDTO> request = ArgumentCaptor.forClass(DancePracticeGenerateDTO.class);
        verify(practiceService).generate(request.capture());
        assertEquals("hiphop", request.getValue().getDanceStyle());
        assertEquals(20, request.getValue().getTargetMinutes());
        verify(redisValues).get(userToken);
        assertNull(ThreadLocalUtil.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 90})
    void acceptsDocumentedDurationBoundaries(int minutes) throws Exception {
        mockMvc.perform(post(GENERATE_PATH)
                        .header("Authorization", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"danceStyle\":\"house\",\"targetMinutes\":" + minutes + "}"))
                .andExpect(status().isOk());

        ArgumentCaptor<DancePracticeGenerateDTO> request = ArgumentCaptor.forClass(DancePracticeGenerateDTO.class);
        verify(practiceService).generate(request.capture());
        assertEquals(minutes, request.getValue().getTargetMinutes());
    }

    @Test
    void requiresLoginBeforeCallingGenerationService() throws Exception {
        mockMvc.perform(post(GENERATE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(practiceService, redisValues);
    }

    @Test
    void signedTokenWithoutActiveSessionIsUnauthorized() throws Exception {
        when(redisValues.get(userToken)).thenReturn(null);

        mockMvc.perform(post(GENERATE_PATH)
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(practiceService);
    }

    @Test
    void administratorDoesNotAcquirePermissionForUserGenerationRoute() throws Exception {
        String token = JwtUtil.generateToken(Map.of(
                JwtClaimsConstant.ADMIN_ID, 3,
                JwtClaimsConstant.ROLE, "ROLE_ADMIN"));

        mockMvc.perform(post(GENERATE_PATH)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isForbidden());

        verifyNoInteractions(practiceService);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequests")
    void invalidInputDoesNotReachGenerationService(String reason, String body) throws Exception {
        mockMvc.perform(post(GENERATE_PATH)
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(practiceService);
    }

    private static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of("empty object", "{}"),
                Arguments.of("null body", "null"),
                Arguments.of("missing style", "{\"targetMinutes\":20}"),
                Arguments.of("null style", "{\"danceStyle\":null,\"targetMinutes\":20}"),
                Arguments.of("blank style", "{\"danceStyle\":\"\",\"targetMinutes\":20}"),
                Arguments.of("unsupported style", "{\"danceStyle\":\"ballet\",\"targetMinutes\":20}"),
                Arguments.of("missing minutes", "{\"danceStyle\":\"hiphop\"}"),
                Arguments.of("null minutes", "{\"danceStyle\":\"hiphop\",\"targetMinutes\":null}"),
                Arguments.of("below minimum", "{\"danceStyle\":\"hiphop\",\"targetMinutes\":4}"),
                Arguments.of("above maximum", "{\"danceStyle\":\"hiphop\",\"targetMinutes\":91}"),
                Arguments.of("fractional minutes", "{\"danceStyle\":\"hiphop\",\"targetMinutes\":20.5}"),
                Arguments.of("string minutes", "{\"danceStyle\":\"hiphop\",\"targetMinutes\":\"20\"}"),
                Arguments.of("overflow minutes", "{\"danceStyle\":\"hiphop\",\"targetMinutes\":2147483648}"),
                Arguments.of("malformed JSON", "{\"danceStyle\":")
        );
    }

    @Test
    void domainValidationIsReturnedAsBadRequest() throws Exception {
        when(practiceService.generate(any(DancePracticeGenerateDTO.class)))
                .thenThrow(new DancePlaylistValidationException("该舞种没有已配置的音乐风格映射"));

        mockMvc.perform(post(GENERATE_PATH)
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1));
    }
}
