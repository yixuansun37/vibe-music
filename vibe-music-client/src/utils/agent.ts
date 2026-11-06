/**
 * VibeAgent 相关的解析工具
 */

/** agent 推荐的一首歌曲（与后端 SongVO 对应） */
export interface VibeSong {
  songId: number
  songName: string
  artistName: string
  album: string
  duration: string
  coverUrl: string
  audioUrl: string
  likeStatus?: number
  releaseTime?: string
}

const VIBE_SONGS_TAG = /<vibe-songs>([\s\S]*?)<\/vibe-songs>/g

/** 标签仍是模型输出：这里只筛选可展示的卡片，保存前须向后端查证。 */
const toVibeSong = (value: unknown): VibeSong | null => {
  if (!value || typeof value !== 'object') return null
  const song = value as Record<string, unknown>
  if (
    !Number.isSafeInteger(song.songId) ||
    (song.songId as number) <= 0 ||
    typeof song.songName !== 'string' ||
    !song.songName.trim()
  ) return null

  return {
    songId: song.songId as number,
    songName: song.songName,
    artistName: typeof song.artistName === 'string' ? song.artistName : '',
    album: typeof song.album === 'string' ? song.album : '',
    duration: typeof song.duration === 'string' ? song.duration :
      typeof song.duration === 'number' && Number.isFinite(song.duration) ? String(song.duration) : '',
    coverUrl: typeof song.coverUrl === 'string' ? song.coverUrl : '',
    audioUrl: typeof song.audioUrl === 'string' ? song.audioUrl : '',
    likeStatus: typeof song.likeStatus === 'number' ? song.likeStatus : 0,
    releaseTime: typeof song.releaseTime === 'string' ? song.releaseTime : undefined,
  }
}

/** 从回复文本中提取 <vibe-songs> 标签里的歌曲列表 */
export const extractVibeSongs = (text: string): VibeSong[] => {
  const songs: VibeSong[] = []
  let match: RegExpExecArray | null
  VIBE_SONGS_TAG.lastIndex = 0
  while ((match = VIBE_SONGS_TAG.exec(text)) !== null) {
    try {
      const parsed = JSON.parse(match[1])
      for (const value of Array.isArray(parsed) ? parsed : [parsed]) {
        const song = toVibeSong(value)
        if (song && !songs.some((existing) => existing.songId === song.songId)) {
          songs.push(song)
        }
      }
    } catch {
      // 忽略无法解析的标签，仅展示文本
    }
  }
  return songs
}

/** 去掉回复文本中的 <vibe-songs> 标签，只保留正文 */
export const stripVibeSongsTag = (text: string): string =>
  text.replace(VIBE_SONGS_TAG, '').trim()
