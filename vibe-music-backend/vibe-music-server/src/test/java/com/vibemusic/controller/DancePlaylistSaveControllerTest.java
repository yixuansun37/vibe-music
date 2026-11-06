package com.vibemusic.controller;

import com.vibemusic.config.RolePathPermissionsConfig;
import com.vibemusic.config.RolePermissionManager;
import com.vibemusic.constant.JwtClaimsConstant;
import com.vibemusic.exception.DancePlaylistValidationException;
import com.vibemusic.handler.GlobalExceptionHandler;
import com.vibemusic.interceptor.LoginInterceptor;
import com.vibemusic.model.dto.DancePlaylistSaveDTO;
import com.vibemusic.result.Result;
import com.vibemusic.service.IPlaylistService;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/**
 * MVC validation and the existing JWT/Redis/role interceptor, without starting
 * Spring Boot or connecting to any external service. Redis and business logic
 * are mocked; authentication decisions and Bean Validation are real.
 */
class DancePlaylistSaveControllerTest {

    private static final String SAVE_PATH = "/playlist/dance/save";
    private static final String VALID_BODY =
            "{\"title\":\"基础练舞\",\"songIds\":[9,2,7]}";

    private IPlaylistService playlistService;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> redisValues;
    private LoginInterceptor loginInterceptor;
    private LocalValidatorFactoryBean validator;
    private MockMvc mockMvc;
    private String userToken;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        playlistService = mock(IPlaylistService.class);
        redisTemplate = mock(StringRedisTemplate.class);
        redisValues = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(redisValues);
        when(redisValues.get(anyString())).thenReturn("active-test-session");

        // Read the application's real role/path rules instead of inventing
        // broader permissions for the test. Loading YAML does not start Boot.
        YamlMapFactoryBean yaml = new YamlMapFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Map<String, Object> document = yaml.getObject();
        assertNotNull(document);
        Map<?, ?> roleConfiguration = (Map<?, ?>) document.get("role-path-permissions");
        RolePathPermissionsConfig permissions = new RolePathPermissionsConfig();
        permissions.setPermissions((Map<String, List<String>>) roleConfiguration.get("permissions"));

        loginInterceptor = new LoginInterceptor();
        ReflectionTestUtils.setField(loginInterceptor, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(loginInterceptor, "rolePermissionManager",
                new RolePermissionManager(permissions));

        PlaylistController controller = new PlaylistController();
        ReflectionTestUtils.setField(controller, "playlistService", playlistService);
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .addInterceptors(loginInterceptor)
                .build();

        // Generated only for this test run; no token or signing key is logged.
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
    void authenticatedUserReceivesCreatedIdAndInputOrderReachesService() throws Exception {
        when(playlistService.saveDancePlaylist(any(DancePlaylistSaveDTO.class)))
                .thenReturn(Result.success(123L));

        mockMvc.perform(post(SAVE_PATH)
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(123));

        ArgumentCaptor<DancePlaylistSaveDTO> dto = ArgumentCaptor.forClass(DancePlaylistSaveDTO.class);
        verify(playlistService).saveDancePlaylist(dto.capture());
        assertEquals("基础练舞", dto.getValue().getTitle());
        assertNull(dto.getValue().getDescription());
        assertEquals(List.of(9L, 2L, 7L), dto.getValue().getSongIds());
        verify(redisValues).get(userToken);
        assertNull(ThreadLocalUtil.get(), "request authentication context must be cleared");
    }

    @Test
    void optionalDescriptionIsAccepted() throws Exception {
        when(playlistService.saveDancePlaylist(any(DancePlaylistSaveDTO.class)))
                .thenReturn(Result.success(74L));

        mockMvc.perform(post(SAVE_PATH)
                        .header("Authorization", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"练舞\",\"description\":\"基础动作练习\",\"songIds\":[2]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(74));

        ArgumentCaptor<DancePlaylistSaveDTO> dto = ArgumentCaptor.forClass(DancePlaylistSaveDTO.class);
        verify(playlistService).saveDancePlaylist(dto.capture());
        assertEquals("基础动作练习", dto.getValue().getDescription());
    }

    @Test
    void missingTokenIsUnauthorizedBeforeServiceInvocation() throws Exception {
        mockMvc.perform(post(SAVE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(playlistService, redisValues);
    }

    @Test
    void malformedJwtIsUnauthorizedEvenWhenRedisHasAnEntry() throws Exception {
        mockMvc.perform(post(SAVE_PATH)
                        .header("Authorization", "Bearer not-a-valid-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(playlistService);
    }

    @Test
    void validJwtWithoutAnActiveRedisSessionIsUnauthorized() throws Exception {
        when(redisValues.get(userToken)).thenReturn(null);

        mockMvc.perform(post(SAVE_PATH)
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(playlistService);
    }

    @Test
    void administratorDoesNotGainUserRoutePermission() throws Exception {
        String adminToken = JwtUtil.generateToken(Map.of(
                JwtClaimsConstant.ADMIN_ID, 3,
                JwtClaimsConstant.ROLE, "ROLE_ADMIN"));

        mockMvc.perform(post(SAVE_PATH)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isForbidden());

        verifyNoInteractions(playlistService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/admin/addPlaylist", "/playlist-binding/addSong"})
    void userStillCannotAccessExistingAdministratorOperations(String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.addHeader("Authorization", "Bearer " + userToken);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(loginInterceptor.preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus());
        verifyNoInteractions(playlistService);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPayloads")
    void invalidInputIsRejectedBeforeServiceInvocation(String reason, String body) throws Exception {
        mockMvc.perform(post(SAVE_PATH)
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(playlistService);
    }

    private static Stream<Arguments> invalidPayloads() {
        String tooManyIds = IntStream.rangeClosed(1, 51).mapToObj(String::valueOf)
                .collect(Collectors.joining(","));
        return Stream.of(
                Arguments.of("null request", "null"),
                Arguments.of("missing title", "{\"songIds\":[1]}"),
                Arguments.of("null title", "{\"title\":null,\"songIds\":[1]}"),
                Arguments.of("empty title", "{\"title\":\"\",\"songIds\":[1]}"),
                Arguments.of("blank title", "{\"title\":\"   \",\"songIds\":[1]}"),
                Arguments.of("title too long", "{\"title\":\"" + "x".repeat(256) + "\",\"songIds\":[1]}"),
                Arguments.of("description too long", "{\"title\":\"练舞\",\"description\":\""
                        + "x".repeat(2001) + "\",\"songIds\":[1]}"),
                Arguments.of("missing song list", "{\"title\":\"练舞\"}"),
                Arguments.of("null song list", "{\"title\":\"练舞\",\"songIds\":null}"),
                Arguments.of("empty song list", "{\"title\":\"练舞\",\"songIds\":[]}"),
                Arguments.of("null song id", "{\"title\":\"练舞\",\"songIds\":[null]}"),
                Arguments.of("zero song id", "{\"title\":\"练舞\",\"songIds\":[0]}"),
                Arguments.of("negative song id", "{\"title\":\"练舞\",\"songIds\":[-1]}"),
                Arguments.of("fractional song id", "{\"title\":\"练舞\",\"songIds\":[1.9]}"),
                Arguments.of("floating point song id", "{\"title\":\"练舞\",\"songIds\":[1.0]}"),
                Arguments.of("string song id", "{\"title\":\"练舞\",\"songIds\":[\"1\"]}"),
                Arguments.of("song id exceeds Long range", "{\"title\":\"练舞\",\"songIds\":[9223372036854775808]}"),
                Arguments.of("more than fifty songs", "{\"title\":\"练舞\",\"songIds\":[" + tooManyIds + "]}"),
                Arguments.of("malformed JSON", "{\"title\":"),
                Arguments.of("nonnumeric song id", "{\"title\":\"练舞\",\"songIds\":[\"not-an-id\"]}")
        );
    }

    @Test
    void missingSongBusinessValidationProducesBadRequest() throws Exception {
        when(playlistService.saveDancePlaylist(any(DancePlaylistSaveDTO.class)))
                .thenThrow(new DancePlaylistValidationException("歌曲不存在"));

        mockMvc.perform(post(SAVE_PATH)
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("歌曲不存在"));
    }

    @Test
    void storageFailureIsNotReportedAsSuccessfulCreation() throws Exception {
        when(playlistService.saveDancePlaylist(any(DancePlaylistSaveDTO.class)))
                .thenThrow(new IllegalStateException("模拟写入失败"));

        mockMvc.perform(post(SAVE_PATH)
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(1));
    }
}
