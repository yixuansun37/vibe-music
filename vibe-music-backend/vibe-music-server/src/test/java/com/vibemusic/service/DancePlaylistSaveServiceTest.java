package com.vibemusic.service;

import com.vibemusic.exception.DancePlaylistValidationException;
import com.vibemusic.mapper.PlaylistBindingMapper;
import com.vibemusic.mapper.PlaylistMapper;
import com.vibemusic.mapper.SongMapper;
import com.vibemusic.model.dto.DancePlaylistSaveDTO;
import com.vibemusic.model.entity.Playlist;
import com.vibemusic.model.entity.PlaylistBinding;
import com.vibemusic.model.entity.Song;
import com.vibemusic.result.Result;
import com.vibemusic.service.impl.PlaylistServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 使用真实 Spring 事务拦截器及 mock 事务管理器，验证提交/回滚边界。
 * 不启动 Spring Boot，不连接任何外部服务；不模拟真实 MySQL 存储引擎。
 */
@ExtendWith(MockitoExtension.class)
class DancePlaylistSaveServiceTest {

    @Mock
    private PlaylistMapper playlistMapper;
    @Mock
    private PlaylistBindingMapper bindingMapper;
    @Mock
    private SongMapper songMapper;
    @Mock
    private PlatformTransactionManager transactionManager;
    @InjectMocks
    private PlaylistServiceImpl target;

    private IPlaylistService service;
    private TransactionStatus transaction;

    @BeforeEach
    void createTransactionalService() {
        transaction = new SimpleTransactionStatus();
        when(transactionManager.getTransaction(any())).thenReturn(transaction);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        service = (IPlaylistService) proxy.getProxy();
    }

    @Test
    void savesNewIdDescriptionAndBindingsInRequestOrderThenCommits() {
        // 数据库查询顺序故意与请求不同，不能据此改变歌曲选择或封面。
        Song first = song(7L);
        first.setCoverUrl("/covers/7.png");
        when(songMapper.selectBatchIds(List.of(7L, 2L))).thenReturn(List.of(song(2L), first));
        insertPlaylistSuccessfully();
        when(bindingMapper.insert(any(PlaylistBinding.class))).thenReturn(1);
        DancePlaylistSaveDTO dto = request(7L, 2L);
        dto.setTitle("  基础练舞  ");
        dto.setDescription("  练习用歌曲  ");

        Result<Long> result = service.saveDancePlaylist(dto);

        assertEquals(0, result.getCode());
        assertEquals(123L, result.getData());
        ArgumentCaptor<Playlist> playlist = ArgumentCaptor.forClass(Playlist.class);
        verify(playlistMapper).insert(playlist.capture());
        assertEquals("基础练舞", playlist.getValue().getTitle());
        assertEquals("练习用歌曲", playlist.getValue().getIntroduction());
        assertEquals("/covers/7.png", playlist.getValue().getCoverUrl());
        ArgumentCaptor<PlaylistBinding> bindings = ArgumentCaptor.forClass(PlaylistBinding.class);
        verify(bindingMapper, times(2)).insert(bindings.capture());
        assertEquals(List.of(7L, 2L), bindings.getAllValues().stream().map(PlaylistBinding::getSongId).toList());
        assertTrue(bindings.getAllValues().stream().allMatch(binding -> binding.getPlaylistId().equals(123L)));
        verify(transactionManager).commit(transaction);
        verify(transactionManager, never()).rollback(any());
    }

    @Test
    void descriptionIsOptional() {
        when(songMapper.selectBatchIds(List.of(7L))).thenReturn(List.of(song(7L)));
        insertPlaylistSuccessfully();
        when(bindingMapper.insert(any(PlaylistBinding.class))).thenReturn(1);

        service.saveDancePlaylist(request(7L));

        ArgumentCaptor<Playlist> playlist = ArgumentCaptor.forClass(Playlist.class);
        verify(playlistMapper).insert(playlist.capture());
        assertNull(playlist.getValue().getIntroduction());
        verify(transactionManager).commit(transaction);
    }

    @Test
    void duplicateSongIdsAreRejectedBeforeAnyDatabaseAccess() {
        assertThrows(DancePlaylistValidationException.class,
                () -> service.saveDancePlaylist(request(7L, 7L)));

        verifyNoInteractions(playlistMapper, songMapper, bindingMapper);
        assertRolledBack();
    }

    @Test
    void existingTitleIsRejectedBeforeWritingAnything() {
        when(playlistMapper.selectCount(any())).thenReturn(1L);

        assertThrows(DancePlaylistValidationException.class,
                () -> service.saveDancePlaylist(request(7L)));

        verify(playlistMapper, never()).insert(any(Playlist.class));
        verifyNoInteractions(songMapper, bindingMapper);
        assertRolledBack();
    }

    @Test
    void anyMissingSongRejectsEntireRequestBeforeWritingAnything() {
        when(songMapper.selectBatchIds(List.of(7L, 999L))).thenReturn(List.of(song(7L)));

        DancePlaylistValidationException error = assertThrows(DancePlaylistValidationException.class,
                () -> service.saveDancePlaylist(request(7L, 999L)));

        assertTrue(error.getMessage().contains("999"));
        verify(playlistMapper, never()).insert(any(Playlist.class));
        verifyNoInteractions(bindingMapper);
        assertRolledBack();
    }

    @Test
    void noMatchingSongsDoesNotCreateEmptyPlaylist() {
        when(songMapper.selectBatchIds(List.of(999L))).thenReturn(List.of());

        assertThrows(DancePlaylistValidationException.class,
                () -> service.saveDancePlaylist(request(999L)));

        verify(playlistMapper, never()).insert(any(Playlist.class));
        verifyNoInteractions(bindingMapper);
        assertRolledBack();
    }

    @Test
    void failedPlaylistInsertRollsBackWithoutInsertingBindings() {
        when(songMapper.selectBatchIds(List.of(7L))).thenReturn(List.of(song(7L)));
        when(playlistMapper.insert(any(Playlist.class))).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.saveDancePlaylist(request(7L)));

        verifyNoInteractions(bindingMapper);
        assertRolledBack();
    }

    @Test
    void missingGeneratedIdRollsBackWithoutInsertingBindings() {
        when(songMapper.selectBatchIds(List.of(7L))).thenReturn(List.of(song(7L)));
        when(playlistMapper.insert(any(Playlist.class))).thenReturn(1);

        assertThrows(IllegalStateException.class, () -> service.saveDancePlaylist(request(7L)));

        verifyNoInteractions(bindingMapper);
        assertRolledBack();
    }

    @Test
    void secondBindingExceptionTriggersRollbackInsteadOfCommit() {
        when(songMapper.selectBatchIds(List.of(7L, 2L))).thenReturn(List.of(song(7L), song(2L)));
        insertPlaylistSuccessfully();
        when(bindingMapper.insert(any(PlaylistBinding.class))).thenReturn(1)
                .thenThrow(new IllegalStateException("simulated database failure"));

        assertThrows(IllegalStateException.class, () -> service.saveDancePlaylist(request(7L, 2L)));

        verify(playlistMapper).insert(any(Playlist.class));
        verify(bindingMapper, times(2)).insert(any(PlaylistBinding.class));
        assertRolledBack();
    }

    @Test
    void zeroAffectedRowsOnSecondBindingAlsoTriggersRollback() {
        when(songMapper.selectBatchIds(List.of(7L, 2L))).thenReturn(List.of(song(7L), song(2L)));
        insertPlaylistSuccessfully();
        when(bindingMapper.insert(any(PlaylistBinding.class))).thenReturn(1, 0);

        assertThrows(IllegalStateException.class, () -> service.saveDancePlaylist(request(7L, 2L)));

        verify(bindingMapper, times(2)).insert(any(PlaylistBinding.class));
        assertRolledBack();
    }

    private void assertRolledBack() {
        verify(transactionManager).rollback(transaction);
        verify(transactionManager, never()).commit(any());
    }

    private void insertPlaylistSuccessfully() {
        when(playlistMapper.insert(any(Playlist.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Playlist.class).setPlaylistId(123L);
            return 1;
        });
    }

    private static DancePlaylistSaveDTO request(Long... songIds) {
        DancePlaylistSaveDTO dto = new DancePlaylistSaveDTO();
        dto.setTitle("基础练舞");
        dto.setSongIds(List.of(songIds));
        return dto;
    }

    private static Song song(Long id) {
        Song song = new Song();
        song.setSongId(id);
        return song;
    }
}
